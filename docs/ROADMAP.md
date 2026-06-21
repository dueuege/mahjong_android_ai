# Roadmap

Engine-first, then sensors, then the other rulesets. Each phase is independently
useful.

## Phase 0 — Sichuan decision brain ✅ DONE
- [x] Tile model (3 suits, 27 kinds), notation parse/format
- [x] Standard shanten (sequences allowed concealed; PON/KAN melds)
- [x] Seven-pairs (七对) shanten
- [x] Ukeire / tile acceptance, with `seen` subtraction
- [x] Best-discard ranking (shanten, then ukeire)
- [x] 定缺 void-suit forced-discard logic
- [x] Spoken-call parser (`SpeechParser`) — CN numerals/suits/aliases + 碰/杠/胡
- [x] 36 passing unit tests + CLI

## Phase 1 — Android MVP (manual input) 🚧
- [x] Compose screen: tile keyboard, void-suit selector, live advice
- [x] Discard-pond / seen tracker UI (input modes feed `seen`)
- [x] Meld tracking (your 碰/杠 reduce the hand correctly)
- [ ] Persist game state across rotation (ViewModel + rememberSaveable)
- [ ] Build & run on a real device

## Phase 2 — Audio board sensor
- [x] Wire `BoardAudioListener` → `SpeechParser` → `AudioBoardController` → `GameState`
- [x] Mic toggle + runtime permission in the UI
- [ ] Continuous recognition (restart loop or on-device streaming ASR: Vosk / whisper.cpp)
- [ ] Disambiguation UI when ASR is unsure; confirm-with-one-tap
- [ ] Tune vocabulary for Sichuan table slang / regional pronunciations
- [ ] Expand `SpeechParser`: 听用 / 报听 / 查叫, "打/摸/出" verbs, pinyin fallback

## Phase 3 — Camera hand recognition
- [x] Temporal smoothing (`RecognitionSmoother`, median over a window) + tests
- [ ] Collect/label Sichuan tile images (reuse open riichi datasets — 27-kind subset)
- [ ] Train tile detector (YOLO-style) + classifier → export TFLite
- [ ] CameraX `ImageAnalysis` → `HandRecognizer` → temporal smoothing → `setHandCounts`
- [ ] Occasional board-video pass to re-sync the discard pond
- [ ] On-screen overlay aligned to the live preview

## Phase 4 — Scoring & strategy depth
- [x] Sichuan fan/番 scoring (碰碰胡, 清一色, 七对/龙七对, 金钩钓, 根) — `SichuanScore`
- [x] Japanese han·fu→points (standard yaku, fu, dora/ura/aka, limits, yakuman) — `RiichiScore`
- [x] Photo-scoring bridge (`ScoreService`) + Score tab UI + `ScoreCli`
- [ ] Score-context editor UI (dora indicators, ippatsu, haitei, double wind …)
- [ ] Chinese-Official (MCR) 81-fan scoring
- [x] Expected-value discard ranking (weight ukeire by resulting hand value) — `analysis/DiscardEV`
- [x] Push/fold & danger estimation from public discards — `analysis/Danger`
- [x] Monte-Carlo win-rate / race — `analysis/WinRate`
- [x] Situation triage + coach report orchestrator — `analysis/Situation`, `analysis/CoachAnalysis`
- [ ] Per-seat opponent hand-reading (needs a per-seat discard/meld capture flow)

## Phase 5 — More rulesets (reuse :engine pattern)
- [ ] Riichi/Japanese: yaku, dora, furiten, riichi; note discards are usually
      *silent*, so lean on board-video + manual taps over audio
- [ ] Chinese Official (MCR): 81-fan scoring engine

## Phase 5.5 — AI assistant (NL + voice) 🚧
- [x] Engine `Assistant` tool layer (LLM → exact engine), tested
- [x] `LlmClient` abstraction + `ClaudeClient` (anthropic-java, opus-4-8, tool runner)
- [x] Settings (DataStore): backend / API key / model / language / ruleset
- [x] Assistant tab: chat + one-tap voice (single-utterance ASR → LLM)
- [ ] Multimodal: send a hand photo to Claude for tile reading (vision path)
- [ ] Edge backend (`EdgeLlmClient`): on-device model for offline play — see docs/LLM.md
- [ ] API-key proxy backend for any non-personal distribution

## Phase 6 — "Improve itself"
- [ ] Game logging (your decisions vs engine optimal) for post-game review
- [ ] Feedback loop: corrected recognitions become new training data
- [ ] Optional cloud model fallback for hard frames (with a clear privacy switch)

## Cross-cutting
- [ ] Keep `:engine` Android-free and fully unit-tested
- [ ] One-tap correction everywhere a sensor can be wrong
- [ ] Never add an API that captures opponents' concealed tiles
