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

## 2026-05-26 — First successful end-to-end smoke test on emulator

**Reference:** Briefing § "First task: end-to-end inference smoke test."

**Setup:** Pixel 10 Pro XL AVD, Android API 37, arm64 (Apple Silicon
host). LiteRT-LM 0.12.0, gemma-4-E2B-it.litertlm (2.41 GB on disk).
Sideloaded via `adb push` from ~/Downloads. Prompt: "What is the
capital of France?"

**Result:** Engine.initialize() succeeded, Conversation.sendMessage
returned "The capital of France is \*\*Paris\*\*." — markdown
formatting from Gemma's instruction-tuning intact.

**M2 — Cold load time:** ~9.0 seconds wall clock from Retry tap to
`state == Ready`. Emulator's GPU passthrough is virtualized so device
numbers should be faster.

**M5 — Resident memory (the load-bearing question):**

| Stage | VmRSS | VmHWM |
|---|---|---|
| Post-load, pre-inference | **712 MB** | 712 MB |
| Post-first-inference | **1.41 GB** | 1.41 GB |

Even the post-inference peak is **dramatically under** iOS's 3.5 GB
MLX figure and iOS's 2.6 GB LiteRT-LM figure (the comment in
`ModelLoader.swift` claims 2.6 GB resident on iOS via LiteRT-LM).
The pre-inference 712 MB shows the PLE selective loading at work:
the model file is 2.41 GB on disk but only ~30% is faulted in at
startup, expanding as the forward pass touches additional layers.

This is the answer the Android port was built to find. Briefing's
hypothesis was "~1.3 GB resident on Android via LiteRT-LM's PLE +
MatFormer" — we landed at 1.4 GB peak on emulator, within margin
of that target. Real-device numbers will land when the Pixel
arrives; emulator is directionally correct.

**M4 — Decode throughput:** not measured this run. The smoke-test
PromptRunner doesn't expose token-by-token timing — that lands when
we port the streaming Conversation shape from iOS.

**Status:** resolved for emulator. Hardware re-validation deferred
until the physical Pixel arrives.

---

## OPEN — M2/M4/M5: Hardware re-validation

**Reference:** iOS sibling `ARCHITECTURE.md` §13 (M-series metrics);
emulator-baseline numbers above.

**Status:** open, blocked on physical Pixel device. Emulator gave
the directional answer; hardware will calibrate the absolute numbers
that feed iOS `ARCHITECTURE.md` §14 row 2's "minimum supported
device" decision. M4 (decode tok/s) is also gated on Android porting
the streaming Conversation shape — the smoke-test PromptRunner is
one-shot only and doesn't surface per-token timing.

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

## 2026-05-26 — Theme system + rebrand ported from iOS

**Reference:** iOS commits `50bcf4a9` (rebrand + themes + auto-load +
ephemeral hints) and `6fc7cf3b` (Sky theme + 'I don't know').

**Observation:** iOS shipped a 6-theme `enum Theme` (System, Terminal,
Tactical, Parchment, Warm Cream, Sky) backed by `@AppStorage("theme")`,
with theme picker in a toolbar submenu and themed surfaces across chat
background, bubbles (user/model/tool), input field, send button tint,
and a preferred light/dark color scheme. Also rebranded "GemmaPOC" →
"OnBoard AI" (display name + nav title only — bundle ID kept) and
auto-loads the model on first view appearance via `.task`.

**Implication:**
- Added `AppTheme.kt` (`enum class AppTheme`) mirroring the iOS enum
  one-for-one (same 6 cases, same display names, same hex values).
- Updated `GemmaPOCTheme` composable to accept an `AppTheme` and bridge
  it onto Material 3's `ColorScheme`: theme.accent → primary,
  theme.background → background/surface, theme.bubbleText/inputText →
  onBackground/onSurface, theme.modelBubble → surfaceVariant,
  theme.inputBackground → surfaceContainer, theme.inputBorder → outline.
  System falls through to the existing dynamic-color-on-API-31+ logic.
- `ThemePreferences.kt` wraps `SharedPreferences("ui_preferences", ...)`
  for persistence. (DataStore would be more idiomatic, but for one
  string preference it's not worth the dep + Flow plumbing.)
- App display name updated in `strings.xml`. Compose `TopAppBar` title
  is `"OnBoard AI"`. Bundle ID / package name unchanged, matching the
  iOS decision to avoid project regen risk.
- `LaunchedEffect(Unit) { if (state is Idle) loader.load() }` mirrors
  the iOS `.task` auto-load. The "Load model" button stays visible as
  a retry path from the Failed state.
- Most iOS theme properties (`userBubble`, `modelBubble`, `toolBubble`,
  `bubbleText`, `inputBackground`, `inputText`, `inputBorder`) apply
  to surfaces that don't exist on Android yet (no chat view, no input
  field). They're defined on the enum anyway — ready for when chat
  lands. `background` and `accent` (mapped to surface and primary)
  drive the visible UI today.

**Status:** resolved.

---

## OPEN — Port 'I don't know' RAG instruction once search lands

**Reference:** iOS commit `6fc7cf3b` (second half).

**Observation:** iOS added a clause to `formatSearchContext` in
`PromptParsing.swift` that licenses "I couldn't find that" as a valid
answer when web-search results don't address the question — anti-
hallucination protection. The Android port has no search path yet, so
the instruction has nothing to attach to.

**Implication:** when the agentic web-search loop ports from iOS, the
RAG-context formatter must include the same "say the search didn't
surface the answer; do NOT fill from general knowledge" wording.

**Status:** deferred — gated on agentic search landing on Android.

---

## OPEN — Port ephemeral-hints framework once system-prompt path lands

**Reference:** iOS commit `50bcf4a9` (EphemeralHints section).

**Observation:** iOS added `PromptParsing.ephemeralHints(for:)` —
per-turn context snippets that get folded into the user body without
inflating the persistent system prompt. First user is privacy-question
detection (prepends a deployment hint when the user asks "is this
private", "does my data leave my phone", etc.). Threaded through
`PromptRunner.respondStream` + `buildUserBody`.

**Implication:** when Android's `PromptRunner` grows a system-prompt /
multi-turn shape, port the ephemeralHints framework alongside it. The
hint table can be near-identical Kotlin — regex match + prepend.

**Status:** deferred — gated on multi-turn chat + system prompt
landing on Android.

---

## OPEN — Port self-identity prompt rules

**Reference:** iOS commit `50bcf4a9` (rebrand section).

**Observation:** iOS system prompt now instructs the model to
introduce itself as "OnBoard AI" and never name Gemma, Google,
LiteRT, MLX, or any underlying technology. Android has no system
prompt yet.

**Implication:** when the Android `PromptRunner` grows a system prompt
(currently the smoke-test PromptRunner is stateless and prompt-less),
copy the iOS persona text verbatim including the no-name-the-tech
clause.

**Status:** deferred — gated on system-prompt landing on Android.

---

## 2026-05-28 — Agentic web search (Phase 1) working end-to-end

**Reference:** iOS commits `10bdb65a` (agentic web search) + `0d3e31c2`
(dual-provider). Ports the search loop, not yet the hardening passes.

**What landed:**
- `search/WebSearchProvider.kt` — SearchResult + provider interface +
  dependency-free `httpGet` (HttpURLConnection, no OkHttp).
- `search/DuckDuckGoProvider.kt` — HTML scrape of html.duckduckgo.com,
  regex-parsed. Same fragility caveat as iOS: tune regexes when DDG
  reshapes their markup.
- `search/WikipediaProvider.kt` — w/api.php full-text search +
  intro extracts, parsed with org.json (built into Android).
- `search/SearchRouter.kt` — runs both in parallel, Wikipedia-first for
  encyclopedic / named-entity queries (the `looksLikeNamedEntity`
  keyword heuristic catches Gemma's flat lowercase queries).
- `PromptParsing.kt` — parseSearchDirective, stripStraySearchDirective,
  formatSearchContext (with the verbatim-copy + "say so if not found"
  anti-hallucination directives), toolBreadcrumb, and SYSTEM_PERSONA
  (verbatim from iOS PromptRunner.systemPersona).
- `ChatStore` — installs SYSTEM_PERSONA + SamplerConfig(40, 0.95, 0.7)
  on the Conversation, runs the SEARCH: tool loop: user turn → if reply
  is `SEARCH: q`, run providers, fold results back as a follow-up turn,
  re-ask, render grounded answer. Tool-role bubble shows the breadcrumb.
- `ChatMessage` gains a Tool role + source field; ChatView renders tool
  bubbles with a "From <source>" badge over AppTheme.toolBubble.
- AndroidManifest: added INTERNET permission. **First non-on-device
  behavior in the app** — scoped to user-initiated search per
  ARCHITECTURE.md §2 invariant 4. LLM inference still never hits network.

**Verified on emulator (Terminal theme):** "how long is the Stillwater
River in Maine" — model emitted SEARCH:, reformulated to "length of the
Stillwater River in Maine", SearchRouter went Wikipedia-first, returned
the 2+1 merge, and the model answered "11.5-mile-long (18.5 km) side
channel of the Penobscot River" — matches Wikipedia verbatim. ~13 s
round trip (search + 2 inference passes). The exact question that
returned "I don't know" pre-search now grounds correctly.

**Deferred to Phase 2 (still open):** prompt-extraction defense,
post-decode fact-check (unverifiedNumbers), best-of-N rerank, ephemeral
privacy hints, location/time preamble, detail toggle, `/search` slash
command, markdown rendering in bubbles, tappable source links.
(Streaming token output landed 2026-05-28.)

**Status:** resolved for Phase 1.

---

## Template

```
## YYYY-MM-DD — Short title

**Reference:** iOS sibling section or briefing reference
**Observation:** what we saw
**Implication:** what changes (architecture / plan / code)
**Status:** open / resolved / proposed
```
