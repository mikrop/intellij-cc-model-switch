package com.github.mikrop.intellijccmodelswitch

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

// Pure preset logic, without any dependency on the IntelliJ Platform or the file system (unit-testable)

const val DEFAULT_TOKEN_KEY = "ANTHROPIC_AUTH_TOKEN"

// Credential variables Claude Code reads. They are always removed on a switch,
// so a key meant for one provider is never sent to another one.
val AUTH_KEYS: List<String> = listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")

// Selects the Claude Code config folder. It is read from the process environment, so it cannot be switched
// through the "env" section of settings.json and is never part of a preset.
const val PROFILE_KEY = "CLAUDE_CONFIG_DIR"

private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

private const val OPENROUTER_URL = "https://openrouter.ai/api"

data class EnvPreset(
    val id: String,
    val label: String,
    val description: String,
    // false = the preset works without an API token (e.g. Claude login)
    val requiresToken: Boolean,
    // Variable the stored token is written to
    val tokenEnvKey: String,
    // Name the token is stored under; presets with the same tokenId share one token
    val tokenId: String,
    val env: Map<String, String>,
)

// OpenRouter preset: a big model for Opus / Sonnet, a small one for Haiku and subagents
private fun openRouterPreset(id: String, label: String, big: String, small: String) = EnvPreset(
    id = id,
    label = label,
    description = "$big / $small via OpenRouter",
    requiresToken = true,
    tokenEnvKey = DEFAULT_TOKEN_KEY,
    tokenId = "openrouter",
    env = linkedMapOf(
        "ANTHROPIC_BASE_URL" to OPENROUTER_URL,
        // Explicitly empty, so an Anthropic key from the environment is not sent to OpenRouter
        "ANTHROPIC_API_KEY" to "",
        "ANTHROPIC_DEFAULT_OPUS_MODEL" to big,
        "ANTHROPIC_DEFAULT_SONNET_MODEL" to big,
        "ANTHROPIC_DEFAULT_HAIKU_MODEL" to small,
        "CLAUDE_CODE_SUBAGENT_MODEL" to small,
    ),
)

// Default presets (used when envs.json does not exist).
// Tokens are not stored here, they live in the IDE password safe.
val DEFAULT_PRESETS: List<EnvPreset> = listOf(
    EnvPreset(
        id = "default",
        label = "Default",
        description = "Official Anthropic configuration",
        requiresToken = false,
        // The Anthropic API expects an API key in the x-api-key header
        tokenEnvKey = "ANTHROPIC_API_KEY",
        tokenId = "default",
        env = linkedMapOf(
            "ANTHROPIC_BASE_URL" to "https://api.anthropic.com",
            "API_TIMEOUT_MS" to "120000",
        ),
    ),
    openRouterPreset("openrouter-deepseek", "OpenRouter DeepSeek", "~deepseek/deepseek-pro-latest", "deepseek/deepseek-v4.1-flash-1017"),
    openRouterPreset("openrouter-grok", "OpenRouter Grok", "x-ai/grok-4.7", "x-ai/grok-build-0.1"),
    openRouterPreset("openrouter-gpt", "OpenRouter GPT", "openai/gpt-6-sol", "openai/gpt-6-luna"),
    openRouterPreset("openrouter-luna", "OpenRouter GPT Luna", "openai/gpt-6-luna", "openai/gpt-6-luna"),
    openRouterPreset("openrouter-qwen", "OpenRouter Qwen", "qwen/qwen3.8-27b", "qwen/qwen3.8-27b"),
)

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.asString

// Validate the content of envs.json, collecting problems instead of failing on the first one
fun parsePresets(data: JsonElement?, problems: MutableList<String>): List<EnvPreset> {
    val list = (data as? JsonObject)?.get("presets")
    if (list == null || !list.isJsonArray) {
        throw IllegalArgumentException("\"presets\" must be an array")
    }

    val result = mutableListOf<EnvPreset>()
    val seenIds = mutableSetOf<String>()
    list.asJsonArray.forEachIndexed { index, item ->
        val raw = item as? JsonObject
        val id = raw?.get("id").stringOrNull()?.trim().orEmpty()
        if (id.isEmpty()) {
            problems.add("preset #${index + 1} has no \"id\"")
            return@forEachIndexed
        }
        if (id in seenIds) {
            problems.add("duplicate preset id \"$id\"")
            return@forEachIndexed
        }
        val rawEnv = raw?.get("env") as? JsonObject
        if (rawEnv == null) {
            problems.add("preset \"$id\" has no \"env\" object")
            return@forEachIndexed
        }
        val tokenEnvKey = if (raw.has("tokenEnvKey")) raw.get("tokenEnvKey").stringOrNull() else DEFAULT_TOKEN_KEY
        if (tokenEnvKey == null || !ENV_NAME.matches(tokenEnvKey)) {
            problems.add("preset \"$id\": \"tokenEnvKey\" must be an environment variable name")
            return@forEachIndexed
        }
        val tokenId = if (raw.has("tokenId")) raw.get("tokenId").stringOrNull()?.trim() else id
        if (tokenId.isNullOrEmpty()) {
            problems.add("preset \"$id\": \"tokenId\" must be a non-empty string")
            return@forEachIndexed
        }

        val env = linkedMapOf<String, String>()
        for ((key, value) in rawEnv.entrySet()) {
            if (key == PROFILE_KEY) {
                problems.add("preset \"$id\": $PROFILE_KEY is ignored, it cannot be set through settings.json")
            } else if (value is JsonPrimitive) {
                env[key] = value.asString
            } else {
                problems.add("preset \"$id\": value of $key must be a string")
            }
        }

        seenIds.add(id)
        result.add(
            EnvPreset(
                id = id,
                label = raw.get("label").stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                description = raw.get("description").stringOrNull().orEmpty(),
                requiresToken = !(raw.get("requiresToken")?.let { it is JsonPrimitive && it.isBoolean && !it.asBoolean } ?: false),
                tokenEnvKey = tokenEnvKey,
                tokenId = tokenId,
                env = env,
            ),
        )
    }
    return result
}

private fun isAuthKey(key: String, preset: EnvPreset) = key == preset.tokenEnvKey || key in AUTH_KEYS

// Preset variables without credentials
fun presetValues(preset: EnvPreset): List<Pair<String, String>> =
    preset.env.entries.filter { !isAuthKey(it.key, preset) }.map { it.key to it.value }

// Credential variables the preset explicitly sets to an empty value
fun clearedAuthKeys(preset: EnvPreset): List<String> =
    preset.env.entries.filter { it.value.isEmpty() && isAuthKey(it.key, preset) }.map { it.key }

// Token written directly in envs.json (legacy, the password safe takes precedence)
fun legacyToken(preset: EnvPreset): String? = preset.env[preset.tokenEnvKey]?.takeIf { it.isNotEmpty() }

// Does the settings env contain all (non-credential) values of the preset?
fun matchesPreset(env: Map<String, String>, preset: EnvPreset) =
    presetValues(preset).all { (key, value) -> env[key] == value }

// Detect the current preset.
// The last preset switched to wins if settings still match it, otherwise the unique best match.
fun detectPreset(env: Map<String, String>, presets: List<EnvPreset>, activeId: String?): EnvPreset? {
    val active = presets.find { it.id == activeId }
    if (active != null && matchesPreset(env, active)) {
        return active
    }

    val candidates = presets
        .filter { presetValues(it).isNotEmpty() && matchesPreset(env, it) }
        .sortedByDescending { presetValues(it).size }
    if (candidates.size == 1 || (candidates.size > 1 && presetValues(candidates[0]).size > presetValues(candidates[1]).size)) {
        return candidates[0]
    }
    return null
}

// Variables a switch removes: credentials, everything defined by a preset and what the last switch wrote
fun managedKeys(presets: List<EnvPreset>, previouslyWritten: List<String>): Set<String> =
    buildSet {
        addAll(AUTH_KEYS)
        presets.forEach { p ->
            add(p.tokenEnvKey)
            addAll(p.env.keys)
        }
        addAll(previouslyWritten)
    }

// String variables of the "env" section of settings.json (non-string values are skipped)
fun envToRecord(env: JsonObject): Map<String, String> =
    env.entrySet().mapNotNull { (key, value) -> value.stringOrNull()?.let { key to it } }.toMap()

// Build the new "env" section: drop managed variables, keep every other entry as it is (in order),
// then add the preset, the variables it clears and its token. The input is not modified.
fun buildEnv(current: JsonObject, preset: EnvPreset, managed: Set<String>, token: String?): Pair<JsonObject, List<String>> {
    val result = JsonObject()
    for ((key, value) in current.entrySet()) {
        if (key !in managed) {
            result.add(key, value)
        }
    }
    val written = mutableListOf<String>()
    fun put(name: String, value: String) {
        result.addProperty(name, value)
        written.add(name)
    }
    presetValues(preset).forEach { (name, value) -> put(name, value) }
    for (name in clearedAuthKeys(preset)) {
        if (!(token != null && name == preset.tokenEnvKey)) {
            put(name, "")
        }
    }
    if (token != null) {
        put(preset.tokenEnvKey, token)
    }
    return result to written
}
