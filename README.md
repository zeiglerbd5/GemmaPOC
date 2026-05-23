# GemmaPOC — Companion-ai Android port

Android sibling of [Companion-ai](https://github.com/zeiglerbd5/Companion-ai)
(iOS). On-device LLM (Gemma 4 E2B) running locally via Google's
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — same runtime the
iOS app uses through its `LiteRTLM` Swift package, so Android and iOS load
the same `.litertlm` weights and present the same `Engine` / `Conversation`
API surface to the app layer.

Phase 0 is a one-prompt smoke test: load Gemma 4 E2B from a sideloaded
`.litertlm` file, send one hardcoded prompt, render the reply in a Compose
view. No chat history, no tool calling, no web search — those land in
follow-up commits once we know the runtime works end-to-end on the
emulator.

See `POC-NOTES-android.md` for the running observation log and Phase 0
measurements; see Companion-ai's `ARCHITECTURE.md` for product-level
context.

## Setup

1. Open the project in Android Studio (Gradle Sync downloads
   `com.google.ai.edge.litertlm:litertlm-android:0.12.0` and its
   transitive deps from Google Maven).
2. Sideload the model file — see below.
3. Run on the Android Studio emulator (Pixel-class AVD, arm64 system
   image on Apple Silicon).

## Sideloading the Gemma 4 E2B model

LiteRT-LM is too large to ship inside the APK (~2.4 GB). The app expects
to find `gemma-4-E2B-it.litertlm` under its app-private external-files
directory. This dir is writable via `adb push` without any runtime
permission on API 31+, but does require the app to have been launched
once (so the directory exists).

```bash
# 1. Download the model from Hugging Face (~2.4 GB, one-time):
#    https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
#    → gemma-4-E2B-it.litertlm
#
#    (NOT gemma-4-E2B-it-web.litertlm — that's the WebGPU variant.
#    NOT gemma-4-E2B-it_*.litertlm — those are Intel/Qualcomm-specific.)

# 2. Launch the app once on the emulator/device so its external-files
#    dir is created. The "Load model" button will fail with a FAILED
#    state telling you exactly which path to push to.

# 3. Push the model. With the app's package id baked in:
adb push gemma-4-E2B-it.litertlm \
  /sdcard/Android/data/com.zeiglerbd5.companion.gemmapoc/files/

# 4. Tap "Load model" in the app. First load takes ~10s while LiteRT-LM
#    deserializes and warms up.
```

A DEBUG-only HF auto-download path (mirror of the iOS sibling's
`#if DEBUG` `loadOrDownload()`) is a planned follow-up. Until then,
sideload is the only ingress.

## Non-negotiables

This project inherits Companion-ai §2's invariants:

1. LLM inference is on-device — never to a cloud LLM.
2. No telemetry, no analytics, no phone-home crash reporters.
3. No selling or sharing user data with third parties.
4. Outbound network only for explicit user-initiated tools (web search,
   sending email, fetching a page the user asked for).
5. Every integration is explicit and revocable; nothing on by default.
6. Every action that touches the outside world is logged locally for
   user audit.
7. No model-download URLs in Release builds — sideload-only.

Before adding any new dependency: confirm it isn't a telemetry /
analytics SDK. Current transitive surface for LiteRT-LM 0.12.0 is
`gson`, `kotlin-reflect`, `kotlinx-coroutines-android` — clean.
