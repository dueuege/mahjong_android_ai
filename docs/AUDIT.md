# Codebase audit — improvement points

A running log of things that surfaced during a code-read pass, plus what's
been fixed and what's deliberately deferred. Anything not crossed off is
free to grab in a future session.

## Fixed this pass

- [x] **Score-tab dead-end snap button.** The "📷 Coach tab" button just told the user to leave; replaced with an actual `ActivityResultContracts.TakePicture` flow + FileProvider so it shoots a real photo and pipes it through the LLM vision path.
- [x] **Correction log was invisible.** Settings now has a "Recognizer corrections" section with the JSONL line count + on-disk size + Clear button.
- [x] **Assistant chat didn't auto-scroll.** New turns now `animateScrollTo(maxValue)` so the freshest reply is always visible.
- [x] **`FloatingTileLabels` could throw on a miswired classMap.** Added a `tileIndex in 0 until TILE_KINDS` guard before calling `Tiles.cnName`.
- [x] **Test artifacts in repo root.** `screenshot1.png`, `shot.png`, `ui.xml` deleted.
- [x] **`secrets*.json` gitignore widened.** A per-provider variant (`secrets.openrouterfree.json`) appeared and the original literal pattern wouldn't have caught it.

## Worth doing next

- [x] **Engine `pick_void_suit` tool.** Added — returns per-suit counts + the lowest-count suit. Wired into both OpenAiClient (auto via `Assistant.tools`) and ClaudeClient (new `PickVoidSuitTool : Supplier<String>`).
- [ ] **`rememberSaveable` for `GameState`.** Currently rotation-only (saved by `configChanges` in the manifest) but lost across process death. A small `Saver<GameState, Bundle>` would survive Don't-Keep-Activities and OOM kills. See ROADMAP Phase 1.
- [ ] **Vision prompt — Japanese honors.** `LlmClient.recognizeHand` returns a 27-length array; the prompt only asks for `m/p/s`. For the Score tab Japanese mode, the LLM should be allowed to emit `z1..z7` and the parser/converter should widen to 34. Today Japanese photo scoring silently drops honors.
- [x] **`OnnxHandRecognizer` class-map validation.** Added a one-shot `Log.i` on the first detection showing `tileIndex=N (cnName) score=X` so a misordered model jumps out in logcat the first time the user points at a known tile.
- [x] **Coach LLM-vision busy indicator.** LlmHandRecognizer publishes an `onBusy(Boolean)` event around each in-flight call; the snap button swaps the 📸 glyph for a `CircularProgressIndicator` while the model is thinking.
- [x] **Auto-prune corrections.** Soft cap at `CorrectionLog.MAX_ENTRIES = 100` JSONL lines + their frames; pruned on every `log()` so the dir stays bounded (~3 MB at the cap).
- [ ] **`LocalLifecycleOwner` deprecation.** `CameraPreview.kt` imports the deprecated `androidx.compose.ui.platform.LocalLifecycleOwner`. Move to `androidx.lifecycle.compose.LocalLifecycleOwner` and add the `androidx.lifecycle:lifecycle-runtime-compose` dep when convenient.
- [ ] **Continuous ASR.** `BoardAudioListener` is single-utterance per its own comment. Restart loop or swap in Vosk / whisper.cpp to keep the table-listen mode actually live.

## Deliberately deferred

- **Re-train detector on MJOD-2136 (Apache 2.0).** Tracked in `docs/ONNX_VISION.md` — clean-license replacement for the vendored ONNX once we know the current model works.
- **Edge LLM (`EdgeLlmClient`) actual implementation.** Useful when offline; depends on a small on-device model picker — see `docs/LLM.md`.
- **Score-context editor UI** (dora indicators, ippatsu, haitei, double-wind). Engine already supports them via `RiichiContext`; UI surface is the missing piece.
- **Chinese-Official (MCR) 81-fan scoring engine.** Tracked in ROADMAP Phase 4.

## Won't fix / not real

- `RevealedHandRecognizer` still ships as a stub in `vision/`. It's unused since the Score tab now uses `LlmClient.recognizeHand` directly. Leaving it in case a multi-player showdown photo scoring path returns; it's free to delete if it's never wired.
- `BoardAudioListener.onError(): restart()` has a `TODO: backoff on repeated errors`. Acceptable for now — the SpeechRecognizer self-throttles when it's misbehaving.
