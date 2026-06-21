# Japanese riichi mahjong — strategy reference

Human-readable companion to the in-code coaching guide
(`app/.../coach/RiichiStrategy.kt`, `RiichiStrategy.GUIDE`), used by the LLM
coach when the ruleset is Japanese. The engine supplies shanten / ukeire /
scoring; this is the reasoning framework.

## Rules that drive strategy

- **34 kinds**: 万 m / 筒 p / 索 s, 1–9 each, plus honors (winds 東南西北,
  dragons 白發中).
- **Yaku required**: you cannot win without at least one yaku (役). No-yaku
  tenpai can't ron or tsumo.
- **Riichi**: closed-hand tenpai may declare riichi (1000-point stick); the hand
  is then locked. Adds a yaku + ura-dora chance, but announces you're tenpai.
- **Furiten**: you cannot ron on a tile you've already discarded (or any tile in
  your wait if one is in your discards) — only tsumo.

## Yaku & value

Common yaku: riichi, tanyao (all 2–8), yakuhai (seat/round wind or dragon
triplet), pinfu (all sequences, ryanmen wait, valueless pair), iipeiko, sanshoku,
ittsu, honitsu/chinitsu, chiitoitsu, toitoi. Score = han + fu → points;
**dora / aka-5 / ura-dora / ippatsu** add han. Open melds (副露) cost the
closed-hand yaku, so only open when you keep a yaku (yakuhai, tanyao, sanshoku,
ittsu, honitsu).

## Efficiency (進張)

Shape quality: **ryanmen** (8 tiles) > kanchan/penchan (4) > shanpon/tanki.
Keep good-shape tenpai. Hold dora-adjacent shapes to add han. Pick the discard
that minimizes shanten and maximizes acceptance; use each wait's remaining count
(shown as `1筒(2)`, or `(2?)` when the discard river is unknown).

## Defense

Read the river when someone declares riichi or opens a threatening hand:

- **Genbutsu** (現物): a tile already in the opponent's discards is 100% safe to
  them.
- **Suji** (筋): if they discarded 4, then 1/7 are safer (no ryanmen wait).
- **Kabe** (壁): if all four of a tile are visible, waits relying on it are
  impossible.
- When your hand is far or your tenpai is cheap, **bail out** (ベタ降り) — don't
  feed a big hand for a small one.

## How the coach should answer

Lead with the conclusion (riichi / which tile to cut / which yaku to build /
whether to fold), then one line of reasoning grounded in acceptance, remaining
counts, han direction (dora/yaku), furiten, and deal-in risk. Concise, spoken.
