package com.github.mikrop.intellijccmodelswitch

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class ClaudeFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun settings(content: String) = tmp.root.toPath().resolve("settings.json").also { Files.writeString(it, content) }

    @Test
    fun `updateEnv changes only the env section`() {
        val path = settings("""{"model":"sonnet","env":{"A":"1"},"permissions":{"allow":["Bash(ls <x>)"]}}""")
        ClaudeFiles.updateEnv(path) { env -> env.also { it.addProperty("B", "2") } }
        val text = Files.readString(path)
        assertTrue(text.endsWith("\n"))
        assertEquals(
            """{"model":"sonnet","env":{"A":"1","B":"2"},"permissions":{"allow":["Bash(ls <x>)"]}}""",
            ClaudeFiles.parseJson(text).toString().replace("\\u003c", "<").replace("\\u003e", ">"),
        )
        // no HTML escaping in the file itself
        assertTrue(text.contains("<x>"))
    }

    @Test
    fun `updateEnv creates a missing file and a missing env section`() {
        val path = tmp.root.toPath().resolve("sub").resolve("settings.json")
        ClaudeFiles.updateEnv(path) { env -> env.also { it.addProperty("A", "1") } }
        assertEquals("""{"env":{"A":"1"}}""", ClaudeFiles.readJson(path).toString())
    }

    @Test
    fun `invalid files are never overwritten`() {
        for (content in listOf("{ not json", "[]", """{"env":[]}""", "")) {
            val path = settings(content)
            try {
                ClaudeFiles.updateEnv(path) { JsonObject() }
                throw AssertionError("expected a failure for: $content")
            } catch (_: IOException) {
            } catch (_: JsonParseException) {
            }
            assertEquals(content, Files.readString(path))
        }
    }

    @Test
    fun `byte order mark is ignored and no temp file is left behind`() {
        val path = settings("﻿{\"env\":{}}")
        ClaudeFiles.updateEnv(path) { env -> env.also { it.addProperty("A", "1") } }
        assertEquals(listOf("settings.json"), Files.list(tmp.root.toPath()).use { s -> s.map { it.fileName.toString() }.toList() })
        assertFalse(Files.readString(path).startsWith("﻿"))
    }

    @Test
    fun `initDefaultEnvs does not overwrite an existing file`() {
        val path = tmp.root.toPath().resolve("envs.json")
        ClaudeFiles.initDefaultEnvs(path)
        assertEquals(ClaudeFiles.defaultEnvsJson(), Files.readString(path))
        Files.writeString(path, "{\"presets\":[]}")
        ClaudeFiles.initDefaultEnvs(path)
        assertEquals("{\"presets\":[]}", Files.readString(path))
    }
}
