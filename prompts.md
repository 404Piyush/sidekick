# Sidekick — Demo prompt library

Five prompts used during the iQOO AI Hackathon demo. Each prompt is chosen
to exercise one of Sidekick's differentiators, not just to produce output.

When timing the demo, the goal is **under 3 minutes per pass**, including
tap-into-app, tap teammate, type prompt, watch response. Most prompts aim
for a 30-90 second response window.

---

## Prompt 1 — On-device code refactor (Coder, USP #2 + #5)

**Tap path:** Home → Coder → paste → Send

```
Refactor this Kotlin class to use Result<T> instead of exceptions:

class UserRepository {
    fun load(id: String): User? {
        return try {
            db.query(id)
        } catch (e: Exception) {
            null
        }
    }
}
```

**What it shows.**:** Real coding model running on the phone. No internet.
No API key. No subscription. Coder's personality surfaces — it pushes back
on the `null` return type, suggesting `Result<User>` makes the failure
explicit.

**Expected timing.** ~90 seconds. Cold-start model latency is the bottleneck.

---

## Prompt 2 — Pull the network cable (offline demo, USP #13)

**Pre-step:** disconnect Wi-Fi on the loaner device before launching the app.

**Tap path:** Home → Coder → paste → Send

```
Write me a Kotlin function that returns the nth Fibonacci number using
recursion. Make sure it handles n=0 and n=1 correctly.
```

**What it shows.**:** The app boots into a local Ollama instance. The URL bar
in the UI is green. The model responds without internet. The privacy-as-
default USP is live — the user literally pulls the cable and Sidekick
keeps working.

**Expected timing.** ~90 seconds. Same as prompt 1 plus a 5-second pause to
show the URL bar is green and Wi-Fi is off.

---

## Prompt 3 — Photo of a stack trace (camera + Coder, USP #6)

**Pre-step:** screenshot an Android stack trace from another app onto the
phone's gallery (or pre-stage a `.png` of a stack trace).

**Tap path:** Home → Coder → tap camera icon → snap the photo of the stack
trace → type "What's wrong?" → Send

```
What's wrong?
```

**What it shows.**:** Coder reads the screenshot of the stack trace, calls
the Read_file-equivalent tool on the image, surfaces a fix. The camera-as-
tool pattern. Multimodal input on-device.

**Expected timing.** ~2 minutes. Photo capture + multimodal model call is
slower than text-only.

---

## Prompt 4 — Build a website (Builder, USP #1)

**Tap path:** Home → Builder → paste → Send

```
Build me a portfolio site for a photographer named Arjun. He shoots
landscapes and portraits. Make it dark and editorial.
```

**What it shows.**:** Builder scaffolds a single-file HTML site. The output
is a real working website the user can preview and share. (Phase 2: the
tool actually saves it and opens in a WebView. Phase 1 demo: the text
describes the HTML.)

**Expected timing.** ~60 seconds. Builder is chatty by design but doesn't
need to stream much code in this Phase 1 demo.

---

## Prompt 5 — Researcher cites sources (Researcher, USP #5)

**Tap path:** Home → Researcher → paste → Send

```
What are the tradeoffs between QNN and Hexagon NPU backends for on-device
LLM inference? Be specific about power consumption and which models work
on each.
```

**What it shows.**:** Researcher's personality surfaces — it cites sources,
admits when a source is weak, surfaces disagreements. The per-teammate
isolation also becomes visible: asking Coder the same question would give
different (more code-focused) results.

**Expected timing.** ~60 seconds. Researcher is trained to be concise.

---

## Demo timing protocol

The 3-minute target comes from the iQOO Hackathon judge's window: ~4
minutes per project, ~30 minutes per group. Three minutes for Sidekick
gives 1 minute for the judge to ask a follow-up question.

A successful demo pass:

1. APK install on loaner device: <30s (pre-staged)
2. Open app: <5s
3. Tap teammate: <5s
4. Type prompt: <30s (use this document; pre-typed on the loaner's clipboard)
5. Watch response: <90s for text-only, <120s for multimodal
6. Close: <10s

**Buffer for cold model load.** First launch after install pulls the
default model. With the batch-pull WorkManager from Phase 2 (#7) the
model is pre-downloaded; without it, budget 5-10 minutes for the pull.
Run the pull during judge warm-up.

---

## Backup prompts (if a primary goes sideways)

**Backup 1 (for prompt 1 failing):**
```
What's the difference between `let`, `apply`, `run`, `with`, and `also`
in Kotlin? When would you reach for each?
```
This is more conversational and works even if the model is sluggish.

**Backup 2 (for prompt 2 failing):**
```
Tell me about your favorite Kotlin language feature.
```
Personality demo only; doesn't depend on tool calls.

**Backup 3 (for prompt 4 failing):**
```
Write a Kotlin function that checks if a string is a palindrome.
```
Classic interview question; Builder handles it gracefully even when
scaffolding fails.

---

## License

MIT. Same as the rest of the repo.