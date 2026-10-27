package com.github.mikrop.intellijccmodelswitch

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.project.DumbAware

private fun switchService() = service<ModelSwitchService>()

// Switch the preset by one click on an entry; the current preset has a check mark
private class PresetAction(private val preset: EnvPreset, isCurrent: Boolean) :
    AnAction(preset.label, preset.description.ifEmpty { null }, if (isCurrent) AllIcons.Actions.Checked else null), DumbAware {
    init {
        // Labels are free text, an underscore in them must not become a mnemonic
        templatePresentation.setText(preset.label, false)
    }

    override fun actionPerformed(e: AnActionEvent) = switchService().switchTo(preset)
}

private fun presetActions(service: ModelSwitchService): List<AnAction> =
    service.presets.map { PresetAction(it, it.id == service.current?.id) }

// Popup of all presets (Tools menu, Search Everywhere)
class SwitchPresetAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val service = switchService()
        service.refresh()
        val group = DefaultActionGroup().apply { presetActions(service).forEach { add(it) } }
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Switch Claude Code Environment", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showInBestPositionFor(e.dataContext)
    }
}

// Store (or remove) the API token of a preset
class SetTokenAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val service = switchService()
        val project = e.project
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(service.presets)
            .setTitle("Set API Token For")
            .setRenderer(SimpleListCellRenderer.create { label, preset, _ -> label.text = preset.label })
            .setSelectedValue(service.current, true)
            .setItemChosenCallback { preset ->
                val sharedWith = service.presets.filter { it.tokenId == preset.tokenId && it.id != preset.id }.map { it.label }
                val message = "Stored in the IDE password safe. Leave empty to remove the stored token." +
                    if (sharedWith.isEmpty()) "" else "\nShared with: ${sharedWith.joinToString(", ")}."
                val value = Messages.showPasswordDialog(project, message, "API Token for ${preset.label} (${preset.tokenEnvKey})", null)
                if (value != null) {
                    service.setToken(preset, value)
                }
            }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}

// Edit the presets file
class EditEnvsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) =
        switchService().openFile(e.project, ClaudeFiles.envsPath(), ClaudeFiles.defaultEnvsJson())
}

// Edit the Claude Code settings.json
class EditSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) =
        switchService().openFile(e.project, ClaudeFiles.settingsPath(), "{}\n")
}

// Popup opened from the status bar widget
fun showQuickActions(dataContext: DataContext) {
    val service = switchService()
    service.refresh()
    val manager = ActionManager.getInstance()
    val group = DefaultActionGroup().apply {
        presetActions(service).forEach { add(it) }
        addSeparator()
        listOf("ccModelSwitch.setToken", "ccModelSwitch.editEnvs", "ccModelSwitch.editSettings")
            .mapNotNull { manager.getAction(it) }
            .forEach { add(it) }
    }
    JBPopupFactory.getInstance()
        .createActionGroupPopup("Claude Code Model Switch", group, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
        .showInBestPositionFor(dataContext)
}

// Settings.json is changed by Claude Code and by hand, so look again when the IDE gets the focus
class RefreshOnActivation : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) = switchService().refresh()
}
