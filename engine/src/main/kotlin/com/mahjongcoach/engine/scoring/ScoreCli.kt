package com.mahjongcoach.engine.scoring

/**
 * Score a revealed hand from the terminal.
 *
 *   java -cp engine.jar com.mahjongcoach.engine.scoring.ScoreCliKt \
 *        sichuan "11223344556677p"
 *
 *   java -cp engine.jar com.mahjongcoach.engine.scoring.ScoreCliKt \
 *        japan "234m567m345p678p55s" 4m ron [dealer] [riichi] [tsumo]
 *
 * Honors in notation: z1..z7 = 東南西北 白發中.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: <sichuan|japan> \"<hand>\" [winTile] [ron|tsumo] [dealer] [riichi]")
        return
    }
    val ruleset = if (args[0].startsWith("j")) Ruleset.JAPANESE else Ruleset.SICHUAN
    val counts = T34.parse(args[1])

    val out = when (ruleset) {
        Ruleset.SICHUAN -> ScoreService.describe(Ruleset.SICHUAN, counts)
        Ruleset.JAPANESE -> {
            val winTile = args.getOrNull(2)?.let { T34.parse(it).indexOfFirst { c -> c > 0 } } ?: -1
            require(winTile >= 0) { "Japanese scoring needs a winning tile, e.g. 4m" }
            val flags = args.drop(3).map { it.lowercase() }
            ScoreService.describe(
                Ruleset.JAPANESE, counts,
                ctx = RiichiContext(
                    winTile = winTile,
                    tsumo = "tsumo" in flags,
                    riichi = "riichi" in flags,
                    seatWind = if ("dealer" in flags) T34.EAST else T34.SOUTH,
                    roundWind = T34.EAST,
                ),
            )
        }
    }
    println(out)
}
