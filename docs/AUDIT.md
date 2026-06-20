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

- [ ] **Engine `pick_void_suit` tool.** The Assistant preset "Pick void suit" today is a pure-LLM call. Adding a deterministic `pick_void_suit(hand)` tool that returns the suit with the lowest combined count + a one-sentence rationale would make the chip's answer authoritative the way the discard tool is.
- [ ] **`rememberSaveable` for `GameState`.** Currently rotation-only (saved by `configChanges` in the manifest) but lost across process death. A small `Saver<GameState, Bundle>` would survive Don't-Keep-Activities and OOM kills. See ROADMAP Phase 1.
- [ ] **Vision prompt — Japanese honors.** `LlmClient.recognizeHand` returns a 27-length array; the prompt only asks for `m/p/s`. For the Score tab Japanese mode, the LLM should be allowed to emit `z1..z7` and the parser/converter should widen to 34. Today Japanese photo scoring silently drops honors.
- [ ] **`OnnxHandRecognizer` class-map validation.** We assume riichi-34 order; if the vendored model has a different label order, every tile is misread. Add a one-time log of the first detected box's class index + assumed tile name so the mismatch is obvious from logcat.
- [ ] **Coach LLM-vision busy indicator.** When the user taps the snap button (LLM mode), there's no on-screen "thinking…" — a small spinner near the FAB during in-flight requests would help.
- [ ] **Auto-prune `corrections/frames/*.jpg`.** Disk usage is bounded only by the user tapping Clear; a soft cap (e.g. delete oldest when > 100 MB) keeps things tidy.
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
