# Tile recognition — model plan

Goal: turn camera frames / still photos into tile counts the engine can use.
Two consumers, one model:

1. **Live** — read **your own hand** continuously (`HandRecognizer`) → counts →
   `GameState.setHandCounts`, smoothed by `RecognitionSmoother`.
2. **Photo scoring** — read a **revealed hand** from a still (`RevealedHandRecognizer`)
   → counts → `ScoreService`.

> Boundary, restated: both read your own hand or tiles revealed at showdown. No
> path captures opponents' concealed tiles or the wall.

## Why on-device (edge) first
- **Private** — table images never leave the phone.
- **Free + offline** — no per-frame API cost, works anywhere.
- **Low latency** — needed for the live "best discard" overlay.
A cloud multimodal fallback is optional for hard frames, behind an explicit
opt-in toggle (Phase 6).

## Pipeline
```
frame ─▶ detector (boxes) ─▶ per-box classifier (1 of N kinds) ─▶ counts ─▶ smoother ─▶ engine
```

### 1. Detector
- Architecture: a small YOLO-style detector (e.g. YOLOv8-n / NanoDet) exported to
  **TFLite** (or ONNX → ORT-Mobile). Targets ~real-time on a mid phone.
- Task: find tile faces. Your own hand is the easy case — a single row, fairly
  fixed angle and lighting.

### 2. Classifier
- 27 kinds for Sichuan (no honors); **34 kinds** for riichi/Chinese (adds winds +
  dragons). Train one 34-class head and mask to 27 for Sichuan.
- Small CNN (MobileNetV3-small / EfficientNet-lite) on the cropped boxes.
- Add an "aka 5" flag (red fives) for riichi dora.

### 3. Temporal smoothing  ✅ implemented + tested
- `engine/.../vision/RecognitionSmoother` keeps a sliding window and reports the
  per-tile **median** count, rejecting single-frame flicker. Pure logic, unit-tested.

## Data
- **Bootstrap** from open mahjong tile datasets (riichi detectors/datasets exist
  on GitHub / Roboflow). Sichuan = the 27-kind subset, so they transfer after
  relabeling/masking honors.
- **Augment**: rotation, perspective warp, lighting/white-balance jitter, motion
  blur, partial occlusion — table conditions are messy.
- **Self-improvement loop (Phase 6)**: when the user corrects a misread via the
  one-tap fix, log the crop + correct label as a new training sample.

## Android wiring
- `CameraX` `Preview` + `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`).
- Run the model in the analyzer on a background executor; convert `ImageProxy`
  (YUV) → RGB/bitmap → input tensor.
- Feed detector→classifier→`RecognitionSmoother.submit(frame)`; when
  `isStable()`, push to `GameState`.
- Overlay boxes/labels aligned to the `PreviewView`.

## Milestones
1. Collect + label a small Sichuan set (own-hand, top-down). Train classifier on
   pre-cropped tiles (skip detection first — assume a roughly aligned row).
2. Add the detector for arbitrary framing.
3. Quantise (int8) + benchmark on-device; target < ~50 ms/frame.
4. Photo-scoring mode: multi-hand clustering for showdown shots.
5. Wire the correction→retraining data loop.

## Accuracy posture
Recognition will never be perfect. The product assumption is **assistive, with a
one-tap correction** everywhere a tile is shown — the engine is exact, the eyes
are best-effort, and the human stays in the loop.
