# HANDOFF — Mahjong Coach

Pick-up notes for continuing on an Android Studio machine. This captures the full
context, every request made, the decisions taken, what's done vs stubbed, and how
to build/run.

---

## 1. What this is

An **Android mahjong coach / training assistant** (not a cheat). It reads the
player's **own hand + public board** and recommends the best play, used openly at
the table — like a riichi efficiency trainer. First ruleset: **Sichuan / 血战到底**;
scoring also covers **Japanese / riichi**.

### The hard boundary (do not cross)
Only ever use information the player is entitled to: **own hand + public info**
(discards, called melds, spoken calls, declared void suit, board video, and
hands *revealed at showdown* for scoring). **Never** capture opponents' concealed
tiles or the wall. A correct mahjong AI reasons probabilistically from public
discards — that's the feature. The vision interfaces intentionally have no
concealed-tile capability.

> Origin/context: the request started as "help me cheat," then the user clarified
> it's casual play with friends, used openly (phone visible). We converged on a
> coach that uses only public info + the player's own hand. That boundary stands
> regardless of how the phone is used — if a future request asks to read
> opponents' concealed tiles, decline.

---

## 2. Every request made (in order) and how each was satisfied

1. **"Build an Android real-time AI guide to help me cheat (Sichuan/Chinese/Japanese)."**
   → Reframed to a **coach** (own hand + public info only). Boundary documented.
2. **Clarified: only with friends, phone used openly.**
   → Accepted as a legitimate openly-used coaching tool.
3. **"Your decisions now."** Chosen: **Sichuan first**, **engine-first** (build and
   test the decision brain before any UI/sensors).
   → Pure-Kotlin engine: tiles, shanten (standard + 七对), ukeire, best-discard,
     定缺, `SpeechParser`, CLI. Tested.
4. **"Do all 3 (pond+meld UI, audio→state, tile-recognition plan) and add a points
   calculator from full/single player photos (Japan or Sichuan)."**
   → Discard-pond + meld UI; audio→GameState wiring + mic; `RecognitionSmoother`
     + photo-scoring interface + `docs/VISION.md`; **full scoring engine** (Sichuan
     番/倍 + Japanese han·fu→points) exposed via Score tab, `ScoreCli`, `ScoreService`.
     Photo scoring is for **revealed** hands only.
5. **"Improve UI + more customizations + Settings + a voice button (one utterance →
   ASR → straight to LLM). Edge or API for the LLM?"**
   → **Answer: API-first (Claude), engine stays the authority; edge = future
     offline fallback.** Built: engine `Assistant` tool layer, `LlmClient` +
     `ClaudeClient` (anthropic-java, `claude-opus-4-8`) + `EdgeLlmClient` stub,
     DataStore Settings + Settings tab, Assistant tab with chat + 🎤 one-tap voice.
6. **"Conclude and hand off; moving to an Android Studio PC."** → this file + commit.

---

## 3. Key decisions

- **Ruleset order:** Sichuan → (Chinese / Japanese reuse the engine pattern).
- **Architecture:** deterministic **engine is the single source of truth**; every
  sensor/UI/LLM layer just feeds tiles in and renders advice.
- **LLM = API-first (Claude `claude-opus-4-8`), adaptive/none thinking, tool-use.**
  The model orchestrates + explains; it **must call the engine** for any
  shanten/ukeire/discard/score number (enforced in `Assistant.SYSTEM_PROMPT`).
  Swappable behind `LlmClient`; edge is a planned offline fallback.
- **API key:** fine for a personal build (entered in Settings). **Do not ship an
  APK with a baked-in key** — proxy through a backend for any distribution.
- **One-tap correction** everywhere a sensor can be wrong; the engine is exact,
  the eyes/ears are best-effort, the human stays in the loop.

---

## 4. Status — verified vs stubbed

### ✅ Done & JVM-tested (80/80) — `:engine` (pure Kotlin, no Android)
- `Tile.kt`, `Hand.kt`, `Shanten.kt` (standard + 七对), `Advisor.kt` (ukeire +
  best discard + 定缺), `SpeechParser.kt`, `Cli.kt`.
- `scoring/` — `Tiles34.kt` (34 kinds incl honors), `WinDecomposer.kt`,
  `SichuanScore.kt`, `RiichiScore.kt`, `ScoreService.kt`, `ScoreCli.kt`.
- `vision/RecognitionSmoother.kt` (median temporal filter).
- `Assistant.kt` — LLM tool layer (`dispatch` → Advisor/ScoreService) + system prompt.
- Tests: `engine/src/test/.../Tests.kt` (a plain `main()`, no Gradle needed).

### 🚧 Written, compiles in Android Studio (NOT built here — no SDK/Gradle) — `:app`
- 4 Compose tabs (`App()` in `MainActivity`): **Coach** (`CoachScreen` + `GameState`),
  **Score** (`score/ScoreScreen`), **Assistant** (`assistant/AssistantScreen` + voice),
  **Settings** (`settings/SettingsScreen` + `data/Settings` DataStore).
- Sensors feed `GameState`: `vision/HandRecognizer`, `vision/RevealedHandRecognizer`
  (photo scoring), `audio/BoardAudioListener` → `audio/AudioBoardController`,
  `audio/SpeechToText` (one-shot ASR).
- LLM: `llm/LlmClient` ← `ClaudeClient` (anthropic-java) / `EdgeLlmClient` (stub) /
  `DisabledLlm`.

### ❌ Stubs (need a model / dataset / further work)
- Camera tile recognition model (`HandRecognizer`, `RevealedHandRecognizer`) — plan
  in `docs/VISION.md`.
- Continuous ASR for the board (single-utterance assistant ASR works; the
  always-on `BoardAudioListener` needs a restart loop or on-device streaming ASR).
- Edge on-device LLM (`EdgeLlmClient`) — plan in `docs/LLM.md`.

---

## 5. Build & run

### Engine only (no Android) — already works on this machine
Requires a JDK + Kotlin compiler (`brew install kotlin`).
```bash
# Tests (expect: 80 passed, 0 failed)
kotlinc engine/src/main/kotlin engine/src/test/kotlin -include-runtime -d engine/build/engine-test.jar
java -cp engine/build/engine-test.jar com.mahjongcoach.engine.TestsKt

# Advice CLI
kotlinc engine/src/main/kotlin -include-runtime -d engine/build/engine.jar
java -cp engine/build/engine.jar com.mahjongcoach.engine.CliKt "123m456m789m1199p5s" s

# Score CLI
java -cp engine/build/engine.jar com.mahjongcoach.engine.scoring.ScoreCliKt sichuan "11223344556677p"
java -cp engine/build/engine.jar com.mahjongcoach.engine.scoring.ScoreCliKt japan "234m567m345p678p55s" 4m ron
```

### Full app — ON THE ANDROID STUDIO PC
1. Open the repo root in Android Studio (it's a Gradle multi-module project:
   `settings.gradle.kts` includes `:engine` + `:app`).
2. Let Gradle sync (downloads the Android SDK 34, Compose, CameraX, anthropic-java).
3. `./gradlew :engine:test` runs the engine tests via Gradle too.
4. Run `:app` on a device/emulator (minSdk 26).
5. To use the assistant: **Settings tab → Claude API → paste `sk-ant-…` key →
   pick model**. Then **Assistant tab → type or tap 🎤**.

**Build notes / likely first fixes in Android Studio:**
- The Android module was never compiled here. Expect a few small SDK-signature
  fixes the compiler will point out (esp. the anthropic-java tool-runner calls in
  `llm/ClaudeClient.kt` — adjust to the exact installed SDK version if needed).
- If the anthropic-java SDK needs newer Java APIs on your minSdk, enable
  `coreLibraryDesugaring` in `app/build.gradle.kts`.
- Plugin/library versions in the `build.gradle.kts` files may need bumping to
  match the installed Android Studio / AGP.

---

## 6. Conventions (for future edits)
- Tile encoding (engine): index 0..26, `suit = i/9` (0=m万,1=p筒,2=s条), `rank = i%9+1`.
  Scoring uses a 34-kind space (`scoring/T34`) that adds honors; the first 27 align.
- shanten: `-1` = win, `0` = tenpai, `n` = n from tenpai.
- Keep `:engine` Android-free and unit-tested. When adding a sensor, feed
  `GameState` and keep a one-tap correction path. Never add concealed-tile capture.

## 7. Where to look next
- `README.md` — overview + ethics. `CLAUDE.md` — notes for AI sessions.
- `docs/ROADMAP.md` — phased plan and what's checked off.
- `docs/VISION.md` — camera tile-model plan. `docs/LLM.md` — assistant + edge/API.
- **Recommended next task:** the Claude **vision** path — send a hand photo to the
  model to read tiles, which sidesteps training a custom CV model (and feeds both
  the live Coach and the photo Score flow).

## 8. Environment note
This authoring machine had **JDK 11 + kotlinc (brew), but no Gradle and no Android
SDK** — only the engine could be compiled/tested here. The `:app` module is ready
for Android Studio.
