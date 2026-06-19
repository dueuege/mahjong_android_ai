# Mahjong Coach (麻将教练)

An Android **mahjong training assistant**: read *your own* hand, track the
*public* board, and get real-time advice on the best play — used **openly**, the
same way riichi players use efficiency trainers (NAGA, Tenhou analysis).

Starting ruleset: **Sichuan / 血战到底**. Riichi and Chinese-Official come later
(the engine is built as a swappable module so the camera/audio/UI layers stay
shared).

---

## Scope & ethics — read this first

This is a **coach that works only on information you're entitled to**:

| Input | Source | Status |
|-------|--------|--------|
| Your own concealed hand | your camera / manual entry | public to you |
| Discards, called melds, dora-equivalents | friends' spoken calls + occasional board video | public to everyone |
| 定缺 void suit, seat/round | you set it | public |

**It does not — and by design *cannot* — read opponents' concealed hands or the
wall.** A strong mahjong AI never needs to: defense and push/fold come from
reasoning *probabilistically* over the public discards, which is exactly the
skill worth learning. The vision module has no API for capturing other players'
tiles, and that's deliberate. Don't add one.

Use it **openly, with friends who know you're running a coach.** That's the
whole intended use.

---

## Status

- ✅ **`:engine` — decision brain + scoring + LLM tool layer (DONE, 80/80 tests passing).**
  Pure Kotlin/JVM, no Android deps:
  - Tiles, shanten (standard + 七对), ukeire with seen-tile subtraction,
    best-discard ranking, 定缺 forced discards, spoken-call parser (`SpeechParser`).
  - **Scoring** (`scoring/`): winning-hand decomposer, **Sichuan 番/倍**
    (碰碰胡 / 清一色 / 七对 / 龙七对 / 根 …) and **Japanese han·fu→points** (standard
    yaku, fu, dora/ura/aka, limit hands, yakuman) — for revealed hands.
  - **Recognition smoothing** (`vision/RecognitionSmoother`) — median temporal filter.
  - **Assistant tool layer** (`Assistant`) — the LLM-facing bridge; routes every
    shanten/score/discard claim through the exact engine.
- 🚧 **`:app` — Android layer.** Four tabs in Compose:
  - **Coach** — manual hand entry, 定缺, 碰/杠 melds, discard-pond / seen tracking,
    live best-discard advice, and a mic toggle (friends' calls → board state).
  - **Score** — type (or, later, photograph) a revealed hand → points.
  - **Assistant** — chat + one-tap **voice** (ASR → LLM); the model answers by
    calling the engine tools. Backend is **Claude API** (swappable; edge later).
  - **Settings** — backend / API key / model / language / ruleset (DataStore).
  - Camera (`HandRecognizer`, `RevealedHandRecognizer`) and continuous ASR are
    wired stubs with clear TODOs — see [docs/VISION.md](docs/VISION.md) and
    [docs/LLM.md](docs/LLM.md).

## Try the engine right now (no Android needed)

Requires a JDK and the Kotlin compiler (`brew install kotlin`).

```bash
# Build the engine jar
kotlinc engine/src/main/kotlin -include-runtime -d engine/build/engine.jar

# Ask for advice on a 14-tile hand, void suit = sou (条)
java -cp engine/build/engine.jar com.mahjongcoach.engine.CliKt "123m456m789m1199p5s" s

# Score a revealed hand (points calculator)
java -cp engine/build/engine.jar com.mahjongcoach.engine.scoring.ScoreCliKt sichuan "11223344556677p"
java -cp engine/build/engine.jar com.mahjongcoach.engine.scoring.ScoreCliKt japan "234m567m345p678p55s" 4m ron

# Run the test suite (expect: 72 passed, 0 failed)
kotlinc engine/src/main/kotlin engine/src/test/kotlin -include-runtime -d engine/build/engine-test.jar
java -cp engine/build/engine-test.jar com.mahjongcoach.engine.TestsKt
```

Example output:

```
Hand: 123456789m1199p5s 定缺条   (14 tiles)
定缺: holding 1 void-suit tile(s) — dump them first.
Best discards:
  1. discard 5条   -> tenpai  (4 tiles) [定缺 forced]
        accepts: 1筒×2 9筒×2
```

Once Android Studio + the SDK are installed, open the repo root and run the
`:app` module on a device (the engine tests run via Gradle too: `./gradlew :engine:test`).

## Architecture

```
                 ┌─────────── sensors (public info only) ───────────┐
  camera ───────▶│ HandRecognizer  (your hand → tile counts)         │
  microphone ───▶│ BoardAudioListener → SpeechParser (calls → events)│
  board video ──▶│ (occasional re-sync)                              │
                 └───────────────────────┬──────────────────────────┘
                                          ▼
                                    GameState  (your hand + seen tiles + 定缺)
                                          ▼
                          :engine  Advisor  (shanten · ukeire · best discard)
                                          ▼
                                  Compose overlay (advice)
```

The brain (`:engine`) is deterministic and unit-tested; every sensor layer just
feeds tiles into it and renders `Advice`. A wrong tile = wrong advice, so the
UI always offers a **one-tap correction**.

See [docs/ROADMAP.md](docs/ROADMAP.md) for the build order.
