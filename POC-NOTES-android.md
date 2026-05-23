# Android POC Observations Log

Running log of observations from the Android port of Companion-ai. Sibling
of the iOS `POC-NOTES.md` in `zeiglerbd5/Companion-ai`. Format mirrors that
log: dated entry, one observation per entry, links back to either iOS
`ARCHITECTURE.md` section or to the briefing this Android workstream
started from.

---

## 2026-05-22 — Runtime decision: LiteRT-LM, not MediaPipe Tasks

**Reference:** iOS sibling `ARCHITECTURE.md` §4 (LLM runtime); project
briefing for this Android port.

**Observation:** The briefing called the runtime "MediaPipe LLM Inference
SDK" and the model file ".task bundle." Reading the iOS sibling's
`ModelLoader.swift` / `PromptRunner.swift` revealed that iOS doesn't use
MediaPipe Tasks — it uses `LiteRTLM` Swift bindings directly, loading
`gemma-4-E2B-it.litertlm` from the `litert-community/gemma-4-E2B-it-litert-lm`
HF repo.

On Android the two paths are different products:

- **MediaPipe LLM Inference** (`com.google.mediapipe:tasks-genai:0.10.27`):
  higher-level Task API, consumes `.task` bundles (LiteRT model +
  tokenizer + metadata in one zip), session-based API
  (`LlmInference.LlmInferenceOptions`).
- **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.12.0`):
  lower-level orchestration on top of LiteRT, consumes `.litertlm`
  files, exposes `Engine` / `EngineConfig` / `Conversation` — a 1:1
  match for the iOS LiteRTLM Swift API.

**Decision:** LiteRT-LM. Picking the runtime that matches the iOS shape
means `ModelLoader.kt` and `PromptRunner.kt` can be near-line-for-line
translations of their iOS siblings, and the Android and iOS apps load
the same model file. The briefing's stated motivation for picking LiteRT
("PLE caching + MatFormer selective activation, ~1.3 GB resident target")
applies equally to both stacks since they're both layered on LiteRT — so
nothing is lost by skipping MediaPipe Tasks here.

**Status:** resolved.

---

## 2026-05-22 — Dependency surface audit (clean)

**Reference:** Briefing non-negotiable #2 ("no telemetry, no analytics,
no crash reporters that phone home").

**Observation:** `com.google.ai.edge.litertlm:litertlm-android:0.12.0`
declares three transitive dependencies in its POM:

- `com.google.code.gson:gson:2.13.2`
- `org.jetbrains.kotlin:kotlin-reflect:2.2.21`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`

None of these phone home or collect telemetry. License: Apache 2.0.
Source: `https://github.com/google-ai-edge/LiteRT-LM`.

**Status:** resolved.

---

## OPEN — M2/M4/M5: Emulator-baseline measurements

**Reference:** iOS sibling `ARCHITECTURE.md` §13 (M-series metrics).

**To measure on the Android Studio emulator (Apple Silicon Mac,
arm64 AVD, Pixel-class profile) once the smoke test runs end-to-end:**

- **M2 — Cold load time:** wall-clock from `Engine(config)` →
  `state == Ready`. Briefing says we should expect "slow; that's
  expected" on the emulator.
- **M4 — Decode throughput** (tok/s) on `SMOKE_TEST_PROMPT`. Expect
  to be much slower than the iPhone 17 Pro Max's ~60 tok/s figure
  because the emulator's GPU passthrough doesn't deliver native
  perf.
- **M5 — Resident memory after load:** the question. iOS sees
  ~3.5 GB resident under MLX-Swift, ~2.6 GB resident under
  LiteRT-LM (per the iOS sibling's `ModelLoader.swift` comment).
  Briefing's hypothesis is **~1.3 GB on Android via LiteRT-LM's
  PLE + MatFormer** — confirming or refuting that is the load-
  bearing reason the Android port exists at all. Measure with
  Android Studio's Profiler → Memory view after the first
  `Run prompt` completes.

Hardware-class measurements (real Pixel) come once the device
arrives.

**Status:** open, blocking the M5 architecture decision in
iOS `ARCHITECTURE.md` §14 row 2.

---

## OPEN — Phone-home audit

**Reference:** Briefing non-negotiable #2.

**To do on first `Engine.initialize()` call:** point a proxy (mitmproxy
or Charles) at the emulator and watch for unexpected outbound traffic
from the LiteRT-LM SDK. POM surface is clean (above) but a runtime
audit is needed before any user-facing milestone.

**Status:** open.

---

## OPEN — DEBUG-only HF download path

**Reference:** Briefing § "Track in POC-NOTES-android.md" /
iOS `POC-NOTES.md` 2026-05-16 entry on the iOS DEBUG `loadOrDownload()`.

**Plan:** mirror the iOS `#if DEBUG` `loadOrDownload()` shape with a
`BuildConfig.DEBUG`-gated download path that fetches
`gemma-4-E2B-it.litertlm` from
`https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`
on first run. Cache under `context.cacheDir`. Release builds must not
link or compile the download code — verify with an APK string-symbol
audit (Android equivalent of iOS M1).

**Status:** deferred — gated on smoke test working with sideload first.

---

## Template

```
## YYYY-MM-DD — Short title

**Reference:** iOS sibling section or briefing reference
**Observation:** what we saw
**Implication:** what changes (architecture / plan / code)
**Status:** open / resolved / proposed
```
