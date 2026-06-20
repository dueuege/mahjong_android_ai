# Training a good tile detector (the real fix for "horrible" detection)

## Why the bundled model is bad

`tiles.onnx` is `colonel-aureliano/Embedded-Mahjong-Bot`'s YOLOv8, trained for
a **fixed Raspberry-Pi rack** (top-down, constant lighting, one tile style).
Used handheld at the table — arbitrary angle, lighting, and possibly different
tile artwork — it's badly out of domain. No amount of post-processing tuning
fixes a domain-mismatched model; you need one trained on diverse, real-world
table photos.

## Diagnose first (confirm it's the model, not our code)

The app now logs to `OnnxHandRecognizer` (logcat tag). Point the camera at a
clear hand and read:

```
adb logcat -s OnnxHandRecognizer
```

- `session ready: … 640x640 (modelShape=1x3x-1x-1) … classMap=from-model-names kept=27`
  - confirms input size resolved, and the map came from the model's own names.
- `frame#N rawCandidates=K keptAfterNMS=M top=[5万@0.42, 3条@0.39, …]`
  - **rawCandidates ≈ 0** → preprocessing/model wrong (or nothing in view).
  - **rawCandidates high but tiles wrong** → class-map issue (shouldn't happen
    now that we read model names, but check).
  - **scores all ~0.30** → model is unsure = domain mismatch → retrain (below).

## The fix: retrain on MJOD-2136 (what the linked repo does)

The app is now **self-configuring**: it reads the class `names` from whatever
ONNX you drop in, so any standard Ultralytics export maps correctly. You only
need to produce a better-trained model.

### Fastest path — Google Colab (free GPU), ~1 hour

```python
# 1. install
!pip install ultralytics

# 2. get a mahjong detection dataset in YOLO format. Options:
#    - jaheel/MJOD-2136 (Apache-2.0, real table photos, COCO → convert)
#    - a Roboflow "mahjong" dataset exported as YOLOv8 (one-click export)
#    Put a data.yaml with `names: [m1..m9, p1..p9, s1..s9, z1..z7, ...]`

# 3. train (YOLOv8n is small + fast on a phone; v8s if you have headroom)
from ultralytics import YOLO
model = YOLO("yolov8n.pt")
model.train(data="data.yaml", epochs=80, imgsz=640, batch=32)

# 4. export to ONNX with FIXED input + embedded names (NOT dynamic)
model.export(format="onnx", imgsz=640, opset=12, simplify=True, dynamic=False)
```

`dynamic=False` matters — it bakes a fixed 640×640 input so our recognizer
never has to fall back. The exporter embeds the `names` dict automatically, so
our `mapFromNames` picks up the suit mapping with no code change.

### Drop it in

```
cp runs/detect/train/weights/best.onnx app/src/main/assets/tiles.onnx
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Coach, check the `session ready` log shows `classMap=from-model-names`,
then verify the per-frame `top=[…]` tiles match reality.

### Class-name requirement

Our `mapFromNames` understands these prefixes (case-insensitive):
`m|w|c → man (万)`, `p|t|d → pin (筒)`, `s|b → sou (条)`, plus it drops
`f*` (flowers), `z*` (honors), bare letters, and `s1..s4`-style seasons. So a
`data.yaml` using the common `m1..m9 / p1..p9 / s1..s9 / z1..z7` ordering Just
Works. If your dataset uses different suit letters, extend `suitBase()` in
`OnnxHandRecognizer.kt`.

## Quantize before shipping (optional)

The current fp32 model makes a 140 MB debug APK. For a real build:
`model.export(format="onnx", int8=True, …)` → ~1/4 the size, and set
`ndk { abiFilters += "arm64-v8a" }` to drop the other ABIs' ORT libs.
