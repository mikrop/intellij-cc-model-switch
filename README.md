# Claude Code Model Switch for JetBrains IDEs

> Switch the Claude Code environment between Anthropic-compatible API providers and models from the IDE status bar

An IntelliJ Platform plugin (IntelliJ IDEA, PyCharm, WebStorm, ...) that writes the selected provider preset (base URL, models, API token) into the `env` section of the Claude Code `settings.json`.

It is a port of the VS Code extension [vscode-cc-model-switch](https://github.com/mikrop/vscode-cc-model-switch). Presets are stored in the same `~/.claude/envs.json`, so both tools share them.

- **Plugin ID:** `com.github.mikrop.intellijccmodelswitch`
- **Requires:** IntelliJ Platform 2026.2 (build 262) or newer, Claude Code CLI or the Claude Code plugin

## Features

- **Quick switching** - switch the provider preset in one click from the status bar (`Claude: <preset>`)
- **Secure tokens** - API tokens are kept in the IDE password safe (OS keychain), not in preset files; presets can share one token
- **Custom presets** - add any Anthropic-compatible provider in `envs.json`
- **Tools menu** - the same actions are available under **Tools | Claude Code Model Switch** and in Search Everywhere

## Bundled presets

| Preset | Base URL | Token |
|--------|----------|-------|
| Default (Anthropic) | `https://api.anthropic.com` | not needed (Claude login) |
| OpenRouter DeepSeek / Grok / GPT / GPT Luna / Qwen | `https://openrouter.ai/api` | required, shared by all OpenRouter presets |

## Usage

1. Click `Claude: ...` in the status bar and pick a preset, or use **Tools | Claude Code Model Switch**.
2. Set the API token of a provider once with **Set API Token** (once for all OpenRouter presets).
3. Start a new Claude Code session so it picks up the new environment.

## What switching does

The plugin changes **only** the `env` section of `settings.json` and keeps every other setting as it is.

- Variables defined by any preset (and the ones written by the previous switch) are removed from `env`.
- The credential variables `ANTHROPIC_AUTH_TOKEN` and `ANTHROPIC_API_KEY` (and any custom `tokenEnvKey`) are always removed first, so a key for one provider is never sent to another.
- Other `env` entries are kept unchanged and in order.
- The preset variables and the stored token (under the preset's `tokenEnvKey`) are added.
- A credential variable set to `""` in the preset is written as empty, e.g. `ANTHROPIC_API_KEY` for OpenRouter.
- The file is re-read right before every write. If it is not valid JSON, or `env` is not an object, nothing is written and an error is shown.

Where `settings.json` is: `$CLAUDE_CONFIG_DIR/settings.json` if the IDE was started with `CLAUDE_CONFIG_DIR`, otherwise `~/.claude/settings.json`.

> **Note:** `settings.json` is read by the Claude Code CLI everywhere, so a switch also affects `claude` started in a terminal outside the IDE.

> **Note:** The token of the **active** preset is present in plain text in `settings.json`. Tokens of the other presets stay only in the IDE password safe.

> **Note:** `env` in a project's `.claude/settings.json` or `.claude/settings.local.json` overrides the user `settings.json`. Remove the switched variables there.

> **Note:** A top-level `"model"` in `settings.json` is not changed by switching. If it holds a full Claude model ID, providers that do not set `ANTHROPIC_MODEL` will receive a model name they do not know. Prefer aliases (`sonnet`, `opus`, `haiku`) or remove it.

## Differences from the VS Code extension

- **No profile switching.** `CLAUDE_CONFIG_DIR` is read from the process environment, so it cannot be set through `settings.json`. To use several Anthropic accounts, start the IDE (or the terminal) with the right `CLAUDE_CONFIG_DIR`. The plugin then edits the `settings.json` in that folder.
- **No editor-only scope.** The VS Code extension changes only the editor; this plugin changes the shared `settings.json`.
- **No configuration panel.** Everything is in the status bar popup and the Tools menu.

## envs.json format

`~/.claude/envs.json` is created with the bundled presets on first start. The format is identical to the VS Code extension:

```json
{
  "presets": [
    {
      "id": "openrouter-deepseek",
      "label": "OpenRouter DeepSeek",
      "description": "DeepSeek via OpenRouter",
      "requiresToken": true,
      "tokenEnvKey": "ANTHROPIC_AUTH_TOKEN",
      "tokenId": "openrouter",
      "env": {
        "ANTHROPIC_BASE_URL": "https://openrouter.ai/api",
        "ANTHROPIC_API_KEY": "",
        "ANTHROPIC_DEFAULT_OPUS_MODEL": "~deepseek/deepseek-pro-latest",
        "ANTHROPIC_DEFAULT_SONNET_MODEL": "~deepseek/deepseek-pro-latest",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL": "deepseek/deepseek-v4.1-flash-1017",
        "CLAUDE_CODE_SUBAGENT_MODEL": "deepseek/deepseek-v4.1-flash-1017"
      }
    }
  ]
}
```

| Field | Description |
|-------|-------------|
| `id` | Unique identifier. |
| `label` | Name shown in the UI (defaults to `id`). |
| `description` | Optional description. |
| `requiresToken` | Default `true`; set to `false` for presets that work without a token. |
| `tokenEnvKey` | Variable the token is written to. Default `ANTHROPIC_AUTH_TOKEN` (sent as `Authorization: Bearer`); use `ANTHROPIC_API_KEY` (sent as `x-api-key`) for the official Anthropic API. |
| `tokenId` | Name the token is stored under (defaults to `id`). Presets with the same `tokenId` share one token. |
| `env` | Variables written into `env`. Values must be strings (numbers and booleans are converted). `CLAUDE_CONFIG_DIR` is ignored. |

The token stored in the password safe takes precedence over one written in `env` of a preset.

## Development

```bash
./gradlew test          # unit tests
./gradlew buildPlugin   # build the plugin ZIP into build/distributions
./gradlew runIde        # start a sandbox IDE with the plugin
```

The build needs JDK 21 to run Gradle; the IntelliJ Platform Gradle Plugin provisions the rest. The first build downloads the IDE (several GB).

Install a built ZIP with **Settings | Plugins | gear icon | Install Plugin from Disk...**.

## License

Apache License 2.0, see [LICENSE](LICENSE).

Based on [vscode-cc-model-switch](https://github.com/mikrop/vscode-cc-model-switch) (Apache-2.0), which is based on [vs-cc-switch](https://gitcode.com/TogetherAI/vs-cc-switch) by hepeng (Apache-2.0). Modified: ported to the IntelliJ Platform, switching through the `env` section of `settings.json`, tokens in the IDE password safe. Project structure from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
