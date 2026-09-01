<div align="center">

# Sidekick

**Phone-first AI teammates. Build websites, write code, run research — from your phone, fully offline.**

[Download APK](#-download) · [Features](#-features) · [Architecture](#-architecture) · [Build](#-build-from-source) · [License](#-license)

</div>

---

## What is Sidekick?

Sidekick turns your Android phone into a self-contained AI workstation with three teammates, each specialised for a different kind of work:

- **Coder** — senior engineer for Kotlin, JavaScript, TypeScript, Python. Refactors, debugs, explains tradeoffs.
- **Builder** — scaffolds full-stack web apps from a description. Outputs a complete self-contained HTML document you can preview live on the phone.
- **Researcher** — reads what you give it, summarises, and answers in plain language.

Pick a teammate. Type or attach an image. The reply streams token by token, right on the phone. Files get read and written into a private sandbox. Nothing leaves the device unless you opt in to a cloud model.

The whole project is MIT licensed, single-module Kotlin / Jetpack Compose, and ships with three working LLM providers out of the box: local Ollama, cloud OpenAI, and on-device LiteRT-LM (the phone's own NPU).

---

## Features

**Three teammates, one app.** Coder for code, Builder for full-stack web apps, Researcher for reading and summarisation. Switch instantly from the home screen.

**Runs three LLM providers.** Pick one in Settings; the choice persists across sessions.

| Provider | Where it runs | Best for |
|---|---|---|
| **Local Ollama** | Your PC, on the same WiFi | Big local models, full privacy, GPU offload |
| **Cloud OpenAI** | OpenAI servers | GPT-4o-mini and friends, fastest setup |
| **On-device LiteRT-LM** | Your phone's NPU / GPU / CPU | Truly offline, zero setup after download |

**Tool registry.** The agent loop can read and write files in the app's private sandbox, list directories, and capture photos through the system camera. Every teammate uses the same registry.

**Sandbox-safe file I/O.** All paths resolve against `filesDir`. Path-traversal attempts are rejected. Writes are atomic (`.tmp` then rename).

**Live website preview.** When Builder outputs a full HTML document, a Preview button appears under the bubble. Tap it and the rendered site opens in an in-app WebView, zero network.

**Photo attachment.** Pick a photo from the gallery (no storage permission needed on Android 13+) or capture one with the camera. The image rides with the message through the multimodal pipeline.

**Persistent conversation history.** Every turn, every tool call, every token usage, indexed in Room. Reopen the app days later and your conversations are right where you left them.

**Dark by default.** Ink-wash palette. The on-device image processing toggle is the only setting; everything else is automatic.

---

## Screenshots

> Coming soon. The app is currently shipping video assets for the iQOO Hackathon submission.

---

## Download

The signed release APK is in the **Releases** section on the right. The release is named after the app version (currently `v0.7.0`).

Sideload it on any Android 7.0+ device (API 24+). No Play Store, no account, no internet required.

To run any of the providers you actually need to configure something first:

- **Local Ollama**: install Ollama on your PC, pull a model (`ollama pull qwen2.5-coder:7b`), set the app's base URL to `http://<your-pc-lan-ip>:11434`.
- **Cloud OpenAI**: paste your API key in Settings.
- **On-device LiteRT-LM**: tap "Download model" in Settings. The app pulls an ungated Qwen3-0.6B and lets you run fully offline.

---

## Architecture

Single-module Android app. Kotlin 2.2.21, Jetpack Compose, Room, OkHttp, LiteRT-LM. Built with AGP 8.5.2 and Gradle 8.7.

```
sidekick-android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/
│   │   │   └── system-prompts/      # Coder, Builder, Researcher prompts
│   │   ├── java/com/sidekick/app/
│   │   │   ├── MainActivity.kt      # Entry point + Compose host
│   │   │   ├── agent/               # Agent loop, tool dispatch
│   │   │   ├── data/                # Room entities + DAOs + DB
│   │   │   ├── provider/            # Ollama, OpenAI, LiteRT-LM, router
│   │   │   ├── tools/               # Built-in tool registry
│   │   │   └── ui/                  # HomeScreen, ConversationScreen, theme
│   │   └── res/                     # Adaptive launcher icon, themes
│   ├── src/test/                    # Unit tests
│   ├── src/androidTest/             # Instrumented Compose UI tests
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/
```

### Provider routing

`LlmRouter` selects the active provider based on the saved `ProviderConfigEntity`. All three providers expose the same `LlmClient` interface (`stream(LlmRequest, onChunk)`), so the agent loop and UI are provider-agnostic.

### Agent loop

`AgentLoop.run(messages, tools, ctx, onEvent)`:
1. Build an `LlmRequest` from history + tool schemas.
2. Open a stream, accumulate text deltas.
3. On tool call: dispatch via `ToolRegistry`, append tool result, loop.
4. Terminate on `Done` or `MaxIterationsExceeded`.

### Tool registry

`ToolRegistry` dispatches by name. Built-ins: `read_file`, `write_file`, `list_dir`, `take_photo`. Every tool returns `ToolResult.Ok(output)` or `ToolResult.Err(message)`. Paths are sandboxed against `filesDir` with canonical-path safety checks.

### On-device model download

`OnDeviceModelManager` streams the model file directly into `filesDir/models/` with progress events. Atomic (temp file then rename). Idempotent (skips download when the file already exists).

---

## Build from source

Requires:
- Android SDK with platform 34
- JDK 17 or 21
- Microsoft OpenJDK 21 known-good (`C:\Users\piyus\Microsoft-jdk-21\jdk-21.0.12.1+1` on this machine)

```bash
git clone https://github.com/404Piyush/sidekick-android.git
cd sidekick-android
./gradlew assembleRelease
```

The release APK lands at `app/build/outputs/apk/release/app-release.apk`. Signed with the debug keystore for demo installs.

For development:

```bash
./gradlew test                  # unit tests
./gradlew :app:assembleDebug    # debug APK
./gradlew lintVitalRelease      # release lint
```

---

## Versions

| Component | Version |
|---|---|
| Kotlin | 2.2.21 |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7 |
| Compose BOM | 2024.12.01 (Compose 1.7.6) |
| compileSdk / targetSdk | 34 |
| minSdk | 24 (Android 7.0) |
| Room | 2.8.4 |
| LiteRT-LM | 0.10.0 |
| OkHttp | 4.12.0 |

---

## Roadmap

- [x] M0-M6: chat, agents, providers, UI polish, on-device model
- [x] M7: LiteRT-LM on-device inference with graceful failure
- [x] M7.5: in-app model download + activate flow
- [x] M8: chat UI overhaul, dark theme, animations, live preview
- [ ] M9: image-aware teammates (Gemma3n vision via LiteRT-LM)
- [ ] M10: encrypted conversation history, key-derived

---

## Contributing

Issues and PRs welcome. The code is small enough to read in an afternoon — start at `MainActivity.kt` and follow the call graph.

---

## License

MIT — see [LICENSE](LICENSE).

---

<div align="center">
Built solo by <a href="https://github.com/404Piyush">@404Piyush</a>. MIT licensed.
</div>