# CLAUDE.md — notes for future sessions

## What this is
Android mahjong assistant, starting with **Sichuan / 血战到底**.
Reads the user's own hand + the public board and recommends the best play, used
openly like a riichi efficiency trainer.

## Layout
- `engine/` — **pure Kotlin/JVM, no Android deps.** The decision brain. Keep it
  Android-free so it stays unit-testable and reusable for other rulesets.
  - `Tile.kt` (27 kinds, notation), `Hand.kt`, `Shanten.kt` (standard + 七对),
    `Advisor.kt` (ukeire + best discard + 定缺), `SpeechParser.kt`, `Cli.kt`.
  - `scoring/` — `Tiles34.kt` (34 kinds incl honors), `WinDecomposer.kt`,
    `SichuanScore.kt`, `RiichiScore.kt`, `ScoreService.kt`, `ScoreCli.kt`.
  - `vision/RecognitionSmoother.kt` — median temporal filter (pure).
  - `Assistant.kt` — LLM-facing tool layer (`dispatch` → Advisor/ScoreService) +
    `SYSTEM_PROMPT`. The LLM must route all math through here.
  - Tests in `engine/src/test/.../Tests.kt` — a plain `main()` with PASS/FAIL,
    runnable without Gradle (currently 80 checks).
- `app/` — Android (Compose), four tabs (`App()` in `MainActivity`):
  - **Coach** (`coach/CoachScreen` + neighbours) — landscape-locked camera-first
    AR HUD: full-bleed CameraX preview, top AdviceBanner, floating per-tile
    labels from the detector (`FloatingTileLabels`), bottom hand-strip
    fallback for count-only recognizers, edit FAB → `EditHandSheet` modal,
    "Go to" sheet footer for nav. Hides the tab bar while foreground.
    Other tabs pin portrait via `LockOrientation`.
  - **Score** (`score/ScoreScreen`) — manual notation or "From gallery"
    photo pick → `LlmClient.recognizeHand` → engine score.
  - **Assistant** (`assistant/AssistantScreen`) — chat + voice + preset
    strategy chips (`Presets`) that inject `GameState.toPromptBlock()` so
    the LLM has the current hand without typing.
  - **Settings** (`settings/SettingsScreen`) — backend (Claude/OpenAI-compat/Off),
    JSON paste/copy, Coach live-mode toggles.
  Sensors feed `GameState`: three recognizers behind one `HandRecognizer`
  interface — `OnnxHandRecognizer` (preferred, needs `assets/tiles.onnx`),
  `LlmHandRecognizer` (opt-in, any vision-capable LLM), `StubHandRecognizer`
  (no-op default). Audio: `BoardAudioListener` → `AudioBoardController`.
  Corrections: when the edit sheet's outgoing hand differs from the
  recognizer's last detection, `CorrectionLog` writes JSONL + frame to
  `filesDir/corrections/` — seed data for retraining.
  LLM is optional and swappable: `llm/LlmClient` ← `ClaudeClient`
  (anthropic-java, opus-4-8) / `OpenAiClient` (any `/chat/completions`
  endpoint with function tools) / `EdgeLlmClient` (stub); chosen in
  `data/Settings` (DataStore). All sensors keep the own-hand/public-info
  boundary. See `docs/LLM.md`, `docs/ONNX_VISION.md`, `docs/RUNNING_PLAN.md`.

## Build / test
This Windows dev box has the full Android toolchain — build via the Gradle wrapper:
```powershell
$env:JAVA_HOME    = "C:\Program Files\Android Studio\android-studio-2024.3.1.14-windows\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :app:assembleDebug      # full APK → app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :engine:compileKotlin   # engine-only smoke test
```
JBR is JDK 21; the engine pins JVM-target 11 so its bytecode stays Android-safe.
`kotlinc` is **not** installed here — use Gradle for engine checks.

CLIs (run from a built engine jar via `java -cp ...`):
`...engine.CliKt "<hand>" [m|p|s] [seen]` (advice) ·
`...engine.scoring.ScoreCliKt <sichuan|japan> "<hand>" [winTile] [ron|tsumo] [dealer] [riichi]` (points)

## Conventions
- Tile encoding: index 0..26, `suit = i/9` (0=m万,1=p筒,2=s条), `rank = i%9+1`.
- shanten: `-1` = win, `0` = tenpai, `n` = n from tenpai.
- When adding a sensor, feed `GameState` and keep a one-tap correction path.

## Next up
See `docs/ROADMAP.md`. Immediate: persist `GameState`, discard-pond + meld
tracking UI, then wire audio (Phase 2).
