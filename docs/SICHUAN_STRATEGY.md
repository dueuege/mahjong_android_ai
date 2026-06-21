# Sichuan mahjong (血战到底) — strategy reference

This is the human-readable version of the coaching "guide book" the LLM gets as
context (the condensed in-code copy is `app/.../coach/SichuanStrategy.kt`,
`SichuanStrategy.GUIDE`). The deterministic numbers — shanten, ukeire (进张),
remaining counts, and the 定缺 recommendation — come from the engine; this is
the reasoning framework applied on top of them.

## Rules that drive strategy

- **Three suits only** (万 m / 筒 p / 条 s), no honors or flowers — 27 tile kinds.
- **定缺 (declare a void suit)** before play. You cannot 碰/杠/胡 with the void
  suit, and you must discard all void-suit tiles before any other tile.
- **血战到底**: play continues *after* someone wins — the round runs until three
  players have won or the wall is exhausted. So a round has multiple winners and
  defense matters from start to finish.
- **查叫 / 查大叫**: at exhaustive draw, players who are *not* tenpai pay those who
  are. → being tenpai at the end has real value; avoid being caught not-listening.

## 定缺 selection

Pick the suit you are **weakest** in: fewest tiles, mostly isolated/edge tiles,
and least useful to your other two suits. The engine recommends the lowest-count
suit by default (tap the highlighted chip on Coach). Keep two suits that can
actually form sequences/triplets.

## Hand-value routes (aim high when it doesn't slow tenpai)

| Route | Notes |
|---|---|
| 清一色 (one suit) | Highest common 番. Go for it if your kept tiles are lopsided into one suit. |
| 碰碰胡 (all triplets) | Strong when you hold several pairs early. |
| 七对 / 龙七对 | 龙七对 = 七对 with a four-of-a-kind (一根). |
| 平胡 | Baseline. |

**根** (any four identical tiles, melded or concealed) multiplies the score.
**杠** adds 刮风 (明杠) / 下雨 (暗杠) bonus and an extra draw — but opening 杠 leaks
information and a tile.

## Tile efficiency (进张)

Shape quality, best first:

1. **两面** (e.g. 34 waiting 2/5) — 8 live tiles.
2. **嵌张 / 边张** (kanchan 35→4, penchan 12→3) — 4 live tiles.
3. **对碰 / 单骑** (shanpon / pair wait) — fewest.

Use each wait's **remaining count** (shown on Coach as `1筒(2)` known, or `(2?)`
when the discard pond is empty and the number is only an upper bound). A wait
with few live tiles is unlikely — switch off it if you can.

## Defense (血战-specific)

Because the round continues after wins, you can't just race:

- Watch opponents who have **碰'd two or more** — likely 碰碰胡 or 清一色; don't feed
  them.
- An opponent's **declared void** is public — avoid their dangerous tiles in the
  two suits they keep.
- When you're far ahead, prioritize **tenpai + not dealing in** over a bigger
  hand. "大牌点小牌不亏" (a big hand dealing into a small one is no loss) is a
  fallacy — winning is what counts.
- **顺势而为**: continuously re-read the table and adjust your hand direction. This
  adaptability is what separates strong players from average ones.

## How the coach should answer

Lead with the conclusion (定缺 / which tile to discard / whether you're tenpai),
then one line of reasoning grounded in 进张, remaining counts, hand-value
direction, and defensive risk. Concise, spoken, table-side.

Sources: general Sichuan 血战到底 strategy consensus (e.g.
[网易四川棋牌攻略](https://qp.163.com/cd/strategy/20171024/26915_720573.html),
[知乎 四川麻将技巧](https://zhuanlan.zhihu.com/p/80497271)).
