package com.github.mikrop.intellijccmodelswitch

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private val LOG = logger<ModelSwitchService>()

private const val NOTIFICATION_GROUP = "CC Model Switch"
private const val ACTIVE_PRESET_STATE = "ccModelSwitch.activePresetId"
private const val MANAGED_KEYS_STATE = "ccModelSwitch.managedEnvKeys"

// Switches the Claude Code environment (the "env" section of settings.json) between presets.
// File and password safe access runs on one background thread, so switches and token changes never overlap.
@Service(Service.Level.APP)
class ModelSwitchService {
    @Volatile
    var presets: List<EnvPreset> = DEFAULT_PRESETS
        private set

    // Preset detected in settings.json at the last refresh
    @Volatile
    var current: EnvPreset? = null
        private set

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("CC Model Switch", 1)
    private val listeners = CopyOnWriteArrayList<Runnable>()

    // Last envs.json warning shown, so the same problem is not reported again on every refresh
    private var lastPresetsWarning: String? = null

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun fireChanged() {
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it.run() } }
    }

    private fun submit(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("CC Model Switch operation failed", e)
                notify("Operation failed: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
            }
        }
    }

    // Re-read envs.json and settings.json (they can change outside the IDE)
    fun refresh() = submit {
        loadPresets()
        updateCurrent()
        fireChanged()
    }

    fun switchTo(preset: EnvPreset) = submit { applyPreset(preset) }

    // Store (or, for a blank value, remove) the token of a preset
    fun setToken(preset: EnvPreset, value: String) = submit {
        val token = value.trim()
        PasswordSafe.instance.set(tokenAttributes(preset.tokenId), if (token.isEmpty()) null else Credentials(preset.tokenId, token))

        // Apply right away if the active preset uses this token
        val active = current
        if (active != null && active.tokenId == preset.tokenId) {
            applyPreset(active)
        } else {
            notify(if (token.isEmpty()) "Token for ${preset.label} removed" else "Token for ${preset.label} saved", NotificationType.INFORMATION)
            fireChanged()
        }
    }

    // Open a file in the editor, creating it first if it does not exist
    fun openFile(project: Project?, path: Path, initialContent: String) = submit {
        if (!Files.exists(path)) {
            ClaudeFiles.writeFileAtomic(path, initialContent)
        }
        ApplicationManager.getApplication().invokeLater {
            val target = project ?: ProjectManager.getInstance().openProjects.firstOrNull()
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            if (file != null && target != null && !target.isDisposed) {
                FileEditorManager.getInstance(target).openFile(file, true)
            }
        }
    }

    private fun tokenAttributes(tokenId: String) =
        CredentialAttributes(generateServiceName("CC Model Switch", tokenId))

    // Token for a preset: password safe first, then a token written directly in envs.json (legacy)
    private fun tokenFor(preset: EnvPreset): String? =
        PasswordSafe.instance.getPassword(tokenAttributes(preset.tokenId))?.takeIf { it.isNotEmpty() } ?: legacyToken(preset)

    // Read envs.json. On error the previously loaded presets stay active and the user is told about it.
    private fun loadPresets() {
        val path = ClaudeFiles.envsPath()
        if (!Files.exists(path)) {
            try {
                ClaudeFiles.initDefaultEnvs(path)
            } catch (e: Exception) {
                LOG.warn("Failed to initialize envs.json", e)
                presets = DEFAULT_PRESETS
                return
            }
        }

        val problems = mutableListOf<String>()
        try {
            presets = parsePresets(ClaudeFiles.readJson(path), problems)
        } catch (e: Exception) {
            problems.add(0, e.message ?: e.javaClass.simpleName)
            problems.add("the previously loaded presets are kept")
        }

        val warning = if (problems.isEmpty()) null else "envs.json: ${problems.joinToString("; ")}"
        if (warning != null && warning != lastPresetsWarning) {
            notify(warning, NotificationType.WARNING) { project ->
                openFile(project, ClaudeFiles.envsPath(), ClaudeFiles.defaultEnvsJson())
            }
        }
        lastPresetsWarning = warning
    }

    private fun updateCurrent() {
        val env = try {
            envToRecord(ClaudeFiles.envOf(ClaudeFiles.readSettings()))
        } catch (_: Exception) {
            emptyMap()
        }
        current = detectPreset(env, presets, PropertiesComponent.getInstance().getValue(ACTIVE_PRESET_STATE))
    }

    // Write the preset into the "env" section of settings.json (must run on the background thread)
    private fun applyPreset(preset: EnvPreset) {
        val token = tokenFor(preset)
        val props = PropertiesComponent.getInstance()
        val managed = managedKeys(presets, props.getList(MANAGED_KEYS_STATE) ?: emptyList())

        var written: List<String> = emptyList()
        try {
            ClaudeFiles.updateEnv { env ->
                val (result, keys) = buildEnv(env, preset, managed, token)
                written = keys
                result
            }
        } catch (e: Exception) {
            notify("Cannot switch: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR) { project ->
                openFile(project, ClaudeFiles.settingsPath(), "{}\n")
            }
            return
        }
        props.setValue(ACTIVE_PRESET_STATE, preset.id)
        props.setList(MANAGED_KEYS_STATE, written)
        updateCurrent()
        fireChanged()

        if (token == null && preset.requiresToken) {
            notify("Switched to: ${preset.label} (no API token stored)", NotificationType.WARNING)
        } else {
            notify("Switched to: ${preset.label}. Start a new Claude Code session to use it.", NotificationType.INFORMATION)
        }
    }

    private fun notify(text: String, type: NotificationType, onAction: ((Project?) -> Unit)? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Claude Code Model Switch", text, type)
        if (onAction != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Open file") { onAction(null) })
        }
        notification.notify(null)
    }
}
