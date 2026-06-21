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
  - **Coach** (`coach/CoachScreen` + neighbours) — camera-first AR HUD:
    full-bleed CameraX preview, top guidance banner (`AdviceBanner`: game
    phase / tappable 定缺 chips / discard advice / `可胡:1筒(2?)` waits),
    floating per-tile labels (`FloatingTileLabels`), `🔄 新局` reset, capture-
    mode toggle (手牌/牌池 → own hand vs accumulating discard pond),
    bottom-left live/⏱-interval/牌池/mic badges, `🧠 AI 指导` button, `📸` snap,
    edit FAB → `EditHandSheet`, top-right + sheet-footer nav. Tab bar hidden
    while foreground.
  - **Score** (`score/ScoreScreen`) — manual notation or gallery/camera photo
    → Roboflow (if key) else `LlmClient.recognizeHand` → engine score.
    Two-pane in landscape.
  - **Assistant** (`assistant/AssistantScreen`) — chat + voice + preset
    strategy chips (`Presets`) injecting `GameState.toPromptBlock()`.
  - **Settings** (`settings/SettingsScreen`) — backend, Roboflow key/model,
    JSON paste/copy, Coach live-mode toggles, screen-orientation lock.
  **Detection** (all behind `HandRecognizer.recognize(Bitmap)`): the Coach
  analyzer decodes each frame once (~3fps), keeps the latest for the snap
  button, and feeds the continuous recognizer:
    - `OnnxHandRecognizer` — offline always-on detector; loads the first
      `assets/*.onnx` (ships `mahjong-baq4s-selftrained-*.onnx`), self-configures
      its class map from the model's embedded `names` (handles 1B/2C/3D…
      B=sou C=man D=pin etc.), NNAPI→CPU fallback.
    - `LlmHandRecognizer` — opt-in vision-LLM continuous fallback.
    - `StubHandRecognizer` — no-op default.
    - 📸 snap button = an online `RoboflowInfer` one-shot on the latest frame.
  **Round memory**: `GameState` accumulates the discard pond (`addSeenCounts`);
  `coach/RoundCoach` keeps a persistent LLM conversation per round + injects a
  ruleset-aware strategy guide-book (`SichuanStrategy` / `RiichiStrategy`);
  auto-guidance fires on hand change (throttled). Reset via `resetRound` + 新局.
  Orientation is one app-wide policy from `Settings.orientationLock`
  (auto = sensor). Corrections: `CorrectionLog` writes JSONL + frame to
  `filesDir/corrections/`. LLM swappable: `llm/LlmClient` ← `ClaudeClient` /
  `OpenAiClient` / `EdgeLlmClient`. See `docs/LLM.md`, `docs/SICHUAN_STRATEGY.md`,
  `docs/RIICHI_STRATEGY.md`, `docs/RUNNING_PLAN.md`.

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
