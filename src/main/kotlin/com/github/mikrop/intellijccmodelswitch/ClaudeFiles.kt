package com.github.mikrop.intellijccmodelswitch

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

// Reading and writing of the Claude Code files (envs.json, settings.json); no IntelliJ Platform dependency

// serializeNulls and disableHtmlEscaping keep the rest of the user's settings.json byte-for-byte meaningful
private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

private const val WRITE_ATTEMPTS = 4
private const val WRITE_RETRY_DELAY_MS = 50L

object ClaudeFiles {
    fun claudeDir(): Path = Paths.get(System.getProperty("user.home"), ".claude")

    // Config folder Claude Code uses: CLAUDE_CONFIG_DIR of the IDE process, otherwise ~/.claude
    fun configDir(): Path = System.getenv(PROFILE_KEY)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: claudeDir()

    // Presets are stored in the home directory, shared with the VS Code extension
    fun envsPath(): Path = claudeDir().resolve("envs.json")

    fun settingsPath(): Path = configDir().resolve("settings.json")

    fun defaultEnvsJson(): String = GSON.toJson(
        JsonObject().apply {
            add("presets", GSON.toJsonTree(DEFAULT_PRESETS.map { presetToJson(it) }))
        },
    ) + "\n"

    private fun presetToJson(p: EnvPreset) = JsonObject().apply {
        addProperty("id", p.id)
        addProperty("label", p.label)
        addProperty("description", p.description)
        addProperty("requiresToken", p.requiresToken)
        addProperty("tokenEnvKey", p.tokenEnvKey)
        addProperty("tokenId", p.tokenId)
        add("env", JsonObject().also { env -> p.env.forEach { (k, v) -> env.addProperty(k, v) } })
    }

    // Parse JSON, ignoring a UTF-8 byte order mark (Windows editors may add one)
    fun parseJson(content: String): JsonElement = JsonParser.parseString(content.removePrefix("﻿"))

    fun readJson(path: Path): JsonElement = parseJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8))

    // Create envs.json with the default presets when it does not exist
    fun initDefaultEnvs(path: Path = envsPath()) {
        if (!Files.exists(path)) {
            writeFileAtomic(path, defaultEnvsJson())
        }
    }

    // The "env" section of settings.json. A missing file or section is empty.
    // Throws when the file is not valid JSON or the section is not an object, so it is never overwritten.
    fun readSettings(path: Path = settingsPath()): JsonObject {
        val root = try {
            readJson(path)
        } catch (_: NoSuchFileException) {
            return JsonObject()
        }
        if (!root.isJsonObject) {
            throw IOException("$path must contain a JSON object")
        }
        val env = root.asJsonObject.get("env")
        if (env != null && !env.isJsonObject) {
            throw IOException("\"env\" in $path must be an object")
        }
        return root.asJsonObject
    }

    fun envOf(settings: JsonObject): JsonObject = settings.get("env")?.asJsonObject ?: JsonObject()

    // Replace only the "env" section; every other setting is kept as it is. The file is re-read right before
    // the write, because Claude Code writes it too.
    fun updateEnv(path: Path = settingsPath(), transform: (JsonObject) -> JsonObject) {
        val settings = readSettings(path)
        settings.add("env", transform(envOf(settings)))
        writeFileAtomic(path, GSON.toJson(settings) + "\n")
    }

    // Write a file atomically (temp file + rename), so a crash never leaves a half-written file.
    // A symlink is followed, so the link itself is kept and its target is replaced.
    fun writeFileAtomic(path: Path, content: String) {
        val target = try {
            path.toRealPath()
        } catch (_: NoSuchFileException) {
            path
        }
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = dir.resolve(".${target.fileName}.${ProcessHandle.current().pid()}.${System.nanoTime()}.tmp")
        Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
        try {
            // The file may hold a token; no-op on file systems without POSIX permissions (Windows)
            runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
            var attempt = 1
            while (true) {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: FileSystemException) {
                    // A rename on Windows may fail while another process holds the file
                    val retryable = e is AccessDeniedException || e.javaClass == FileSystemException::class.java
                    if (attempt >= WRITE_ATTEMPTS || !retryable) throw e
                    Thread.sleep(WRITE_RETRY_DELAY_MS * attempt)
                    attempt++
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
