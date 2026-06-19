# CLAUDE.md — notes for future sessions

## What this is
Android mahjong **coach** (training assistant), starting with **Sichuan / 血战到底**.
Reads the user's own hand + the public board and recommends the best play, used
openly like a riichi efficiency trainer.

## Hard boundary (do not cross)
Only ever use information the player is entitled to: **their own hand + public
board (discards, melds, spoken calls, board video).** Never add code that
captures opponents' concealed hands or the wall. The vision interface
intentionally has no such capability. A correct mahjong AI reasons
probabilistically from public discards — that's the feature, not a limitation.

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
- `app/` — Android (Compose), four tabs (`App()` in `MainActivity`): Coach
  (`CoachScreen` + `GameState`), Score (`score/ScoreScreen`), Assistant
  (`assistant/AssistantScreen` + voice), Settings (`settings/SettingsScreen`).
  Sensors feed `GameState`: `vision/HandRecognizer` + `vision/RevealedHandRecognizer`
  + `audio/BoardAudioListener` → `audio/AudioBoardController`. LLM is optional and
  swappable: `llm/LlmClient` ← `ClaudeClient` (anthropic-java, opus-4-8) / `EdgeLlmClient`
  (stub); chosen in `data/Settings` (DataStore). All sensors keep the
  own-hand/public-info boundary. See `docs/LLM.md`.

## Build / test without Android Studio
```bash
kotlinc engine/src/main/kotlin engine/src/test/kotlin -include-runtime -d engine/build/engine-test.jar
java -cp engine/build/engine-test.jar com.mahjongcoach.engine.TestsKt   # expect: 80 passed, 0 failed
```
CLIs: `...engine.CliKt "<hand>" [m|p|s] [seen]` (advice) ·
`...engine.scoring.ScoreCliKt <sichuan|japan> "<hand>" [winTile] [ron|tsumo] [dealer] [riichi]` (points)

Environment note: this machine has JDK 11 + `kotlinc` (brew) but **no Gradle and
no Android SDK** — the `:app` module can't be built here; verify engine changes
via the kotlinc path above.

## Conventions
- Tile encoding: index 0..26, `suit = i/9` (0=m万,1=p筒,2=s条), `rank = i%9+1`.
- shanten: `-1` = win, `0` = tenpai, `n` = n from tenpai.
- When adding a sensor, feed `GameState` and keep a one-tap correction path.

## Next up
See `docs/ROADMAP.md`. Immediate: persist `GameState`, discard-pond + meld
tracking UI, then wire audio (Phase 2).
