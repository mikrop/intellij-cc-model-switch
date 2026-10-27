package com.github.mikrop.intellijccmodelswitch

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    private fun preset(id: String, env: Map<String, String>, tokenEnvKey: String = "ANTHROPIC_AUTH_TOKEN") =
        EnvPreset(id, id, "", true, tokenEnvKey, id, env)

    private fun json(text: String) = JsonParser.parseString(text) as JsonObject

    @Test
    fun `parses the default presets without problems`() {
        val problems = mutableListOf<String>()
        val parsed = parsePresets(json(ClaudeFiles.defaultEnvsJson()), problems)
        assertEquals(emptyList<String>(), problems)
        assertEquals(DEFAULT_PRESETS, parsed)
    }

    @Test
    fun `collects problems and skips invalid presets`() {
        val problems = mutableListOf<String>()
        val parsed = parsePresets(
            json(
                """{"presets":[
                    {"id":"a","env":{"X":1,"CLAUDE_CONFIG_DIR":"/x","Y":{}}},
                    {"id":"a","env":{}},
                    {"env":{}},
                    {"id":"b"},
                    {"id":"c","env":{},"tokenEnvKey":"not valid"},
                    {"id":"d","env":{},"requiresToken":false,"tokenId":"shared"}
                ]}""",
            ),
            problems,
        )
        assertEquals(listOf("a", "d"), parsed.map { it.id })
        assertEquals(mapOf("X" to "1"), parsed[0].env)
        assertEquals(false, parsed[1].requiresToken)
        assertEquals("shared", parsed[1].tokenId)
        assertEquals(6, problems.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file without presets array`() {
        parsePresets(json("{}"), mutableListOf())
    }

    @Test
    fun `credentials are not part of the preset values`() {
        val p = preset("p", mapOf("ANTHROPIC_BASE_URL" to "u", "ANTHROPIC_API_KEY" to "", "ANTHROPIC_AUTH_TOKEN" to "t"))
        assertEquals(listOf("ANTHROPIC_BASE_URL" to "u"), presetValues(p))
        assertEquals(listOf("ANTHROPIC_API_KEY"), clearedAuthKeys(p))
        assertEquals("t", legacyToken(p))
    }

    @Test
    fun `detects the active preset and the unique best match`() {
        val a = preset("a", mapOf("ANTHROPIC_BASE_URL" to "u"))
        val b = preset("b", mapOf("ANTHROPIC_BASE_URL" to "u", "ANTHROPIC_MODEL" to "m"))
        val env = mapOf("ANTHROPIC_BASE_URL" to "u", "ANTHROPIC_MODEL" to "m")
        assertEquals(b, detectPreset(env, listOf(a, b), null))
        assertEquals(a, detectPreset(mapOf("ANTHROPIC_BASE_URL" to "u"), listOf(a, b), null))
        assertEquals(a, detectPreset(env, listOf(a, b), "a"))
        assertNull(detectPreset(mapOf("ANTHROPIC_BASE_URL" to "other"), listOf(a, b), null))
        // two presets matching equally well are ambiguous
        assertNull(detectPreset(mapOf("ANTHROPIC_BASE_URL" to "u"), listOf(a, preset("c", mapOf("ANTHROPIC_BASE_URL" to "u"))), null))
    }

    @Test
    fun `managed keys cover credentials, preset variables and the previous write`() {
        val keys = managedKeys(listOf(preset("a", mapOf("X" to "1"), "MY_TOKEN")), listOf("OLD"))
        assertTrue(keys.containsAll(listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "MY_TOKEN", "X", "OLD")))
    }

    @Test
    fun `buildEnv replaces managed variables, keeps the rest in order and adds the token`() {
        val current = json("""{"KEEP_1":"a","ANTHROPIC_BASE_URL":"old","ANTHROPIC_API_KEY":"secret","KEEP_2":"b","N":5}""")
        val p = preset("p", mapOf("ANTHROPIC_BASE_URL" to "new", "ANTHROPIC_API_KEY" to ""))
        val (env, written) = buildEnv(current, p, managedKeys(listOf(p), emptyList()), "tok")
        assertEquals(
            """{"KEEP_1":"a","KEEP_2":"b","N":5,"ANTHROPIC_BASE_URL":"new","ANTHROPIC_API_KEY":"","ANTHROPIC_AUTH_TOKEN":"tok"}""",
            env.toString(),
        )
        assertEquals(listOf("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"), written)
        // the input is not modified
        assertTrue(current.has("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `buildEnv writes a cleared credential as empty only when no token replaces it`() {
        val p = preset("p", mapOf("ANTHROPIC_API_KEY" to ""), "ANTHROPIC_API_KEY")
        val (withToken, _) = buildEnv(JsonObject(), p, managedKeys(listOf(p), emptyList()), "k")
        assertEquals("""{"ANTHROPIC_API_KEY":"k"}""", withToken.toString())
        val (without, _) = buildEnv(JsonObject(), p, managedKeys(listOf(p), emptyList()), null)
        assertEquals("""{"ANTHROPIC_API_KEY":""}""", without.toString())
    }
}
