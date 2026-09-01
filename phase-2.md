# Sidekick — Phase 2 backlog

This document tracks the work that was deliberately deferred past Phase 1 (the
iQOO AI Hackathon demo). Each item has: a one-line problem, a one-line design
sketch, and the rough effort estimate. The intent is to keep these discoverable
so a future contributor (or future me) doesn't reinvent or duplicate.

Items are ranked by impact-to-effort, top first. The first three are the
strongest candidates for "what would change the demo most."

---

## 1. Real NPU routing via QNN / Hexagon

**Problem.** Today the LLM runs on CPU only. On iQOO devices with the Qualcomm
Hexagon NPU (Snapdragon 8 Elite), routing inference through the NPU is 3-5x
faster than CPU-only and uses 60% less battery. This is the single biggest
"this is the iQOO's home chip" differentiator and we don't exercise it.

**Design sketch.** Drop into `llm.cpp`'s QNN build flags, route
`qwen2.5-coder:7b-q4_k_m` (and `phi4:14b-q4_k_m`) through `QnnBackend` when
`Build.HOST` matches an iQOO SKU (currently `qcom` + specific SoC strings). Add
a runtime probe that benchmarks CPU vs. NPU on first launch and remembers the
winner in `ProviderConfigEntity`. The Ollama provider gets a `runtime` field
on its config; flip to NPU without re-installing.

**Effort.** 2-3 days. Mostly build flags + driver verification on the loaner.

---

## 2. Vision tool call (real multimodal pass)

**Problem.** M4 added camera capture and base64 wire format, but the Ollama
provider logs a warning and the OpenAI provider routes through `gpt-4o-mini`
when no Ollama-side vision model is configured. We don't have a true on-device
multimodal round-trip.

**Design sketch.** Replace the `TakePhoto` stub with a CameraX-based live
capture (PreviewView, ImageCapture). Use Ollama's `qwen2.5-vl:7b` for the
on-device multimodal pass. The teammate system prompt decides whether to call
the vision model or the text model — Coder stays on `qwen2.5-coder:7b`, but a
new "Scout" teammate (or a per-tool vision flag) routes images to the VL
model.

**Effort.** 3-4 days. CameraX is straightforward, but the vision model is ~5GB
and slow on phone; benchmarking required.

---

## 3. Write file tool

**Problem.** ReadFile and ListDir are read-only. Coder says "create a new
Kotlin file" and we can only quote text back. A `WriteFile` tool turns the
agent into something that can actually scaffold projects.

**Design sketch.** Add `WriteFile.kt` with the same sandbox scope as
`ReadFile` (paths under `context.filesDir`, reject `..` traversal). Writes go
through a confirmation step — the agent emits the tool call, the UI shows a
diff preview, the user taps "Apply" before bytes hit disk. This is the
"approval-required" pattern; without it, the agent can clobber the user's
files.

**Effort.** 1 day for the tool, 1 day for the UI confirmation flow.

---

## 4. build_website tool

**Problem.** USP #1 is "build and ship a working website from your phone." The
text model can describe a website. The `Builder` teammate can scaffold
projects in text. Neither can actually produce a working HTML file the user
opens in Chrome.

**Design sketch.** `BuildWebsite.kt` takes the model output (a single HTML
blob), writes it to `filesDir/site.html`, opens it in a Compose `WebView`
preview, and offers "Share URL" via Android's share sheet. The file is
self-contained — no external CDN, no analytics, no telemetry. Required for the
"build a real website" USP.

**Effort.** 1 day for the tool + preview, 0.5 days for share-sheet plumbing.

---

## 5. Encrypted session export

**Problem.** Every conversation is in `databases/sidekick.db`, unencrypted. A
user with root (or `adb backup`) can pull all conversation history. The privacy
pledge on the marketing site ("0 cloud requests unless you opt in") implies
local data should also be private at rest.

**Design sketch.** Wrap Room in SQLCipher (`net.zetetic:android-database-sqlcipher`).
The encryption key is derived from a user-chosen passphrase via Argon2id
(libsodium). Export bundle is `.sidekick-session` JSON containing the
ciphertext + metadata; on import, the user re-enters the passphrase. Backup
opt-out is enforced via `android:allowBackup="false"` and `adb backup`
rejection.

**Effort.** 2 days for SQLCipher + passphrase UI, 1 day for export/import
flow.

---

## 6. Cloud provider UI

**Problem.** M1 wired the OpenAI-compatible provider into `LlmRouter`. The user
can configure it via DataStore, but there's no in-app UI for "paste an API key
and switch to GPT-4o." Today the Settings sheet has a placeholder for the
cloud toggle but no real form.

**Design sketch.** A second screen in the Settings sheet flow: paste an
OpenAI-compatible base URL (defaults to `https://api.openai.com/v1`), paste an
API key (masked after entry, never logged), pick a model from a fetched list
(or type a custom one). On save, persist to `ProviderConfigEntity` and call
`LlmRouter.invalidate(...)`. Same UX as the Ollama model picker — just for the
cloud side.

**Effort.** 1 day. Most of the back-end wiring exists from M1.

---

## 7. Batch model pull

**Problem.** First-token time after install is dominated by model pull. A 7B
Q4_K_M is ~4.7 GB. On a typical home Wi-Fi that's 5-10 minutes. The user is
staring at a progress bar the entire time.

**Design sketch.** A `WorkManager` job (`OneTimeWorkRequest` with `NetworkType.CONNECTED`)
that pre-pulls the user's selected default model in the background while they
read the home screen. The Settings sheet shows "Pulling… 47%" with a
`LinearProgressIndicator`. Cancellation = cancel the WorkRequest. The
`OllamaModelManager.pull()` from M4.5 is the back-end.

**Effort.** 1 day. WorkManager boilerplate + integration with M4.5's pull
flow.

---

## Out of scope (intentional)

These were considered and explicitly excluded from Phase 1. Recording here so a
reviewer asking "why isn't X here?" has an answer.

- **Multi-line tool calls with branching.** Phase 1 is one-shot tool calls. No
  sub-agent recursion, no parallel tool execution.
- **Streaming tool progress.** Tool results land as one block. No "compiling…
  step 3/5" intermediate states.
- **Voice input.** Tribute to iQOO's voice stack, deferred to Phase 2.
- **App widgets / Quick Tiles.** Compose-only.
- **Localization.** English only.
- **Crashlytics / analytics.** None. Privacy-as-default applies to telemetry
  too.

---

## License

MIT. Same as the rest of the repo.