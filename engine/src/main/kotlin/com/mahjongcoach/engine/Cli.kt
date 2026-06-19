package com.mahjongcoach.engine

/**
 * Tiny CLI to drive the engine by hand, before the Android UI exists.
 *
 *   java -cp engine.jar com.mahjongcoach.engine.CliKt "<hand>" [voidSuit] [seen]
 *
 *   <hand>     compact notation, e.g. "123m456m789m1199p5s" (13 or 14 tiles)
 *   voidSuit   optional: m | p | s  (定缺 — the suit you've declared void)
 *   seen       optional: notation of tiles already visible on the table
 *
 * Example:
 *   java -cp engine.jar com.mahjongcoach.engine.CliKt "123m456m789m1199p5s" s
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: <hand> [voidSuit m|p|s] [seenTiles]")
        println("example: \"123m456m789m1199p5s\" s")
        return
    }

    val voidSuit = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Suit.fromCode(it[0]) }
    val seen = args.getOrNull(2)?.let { Tiles.parse(it) } ?: IntArray(Tiles.TILE_KINDS)

    val hand = Hand.of(args[0], voidSuit = voidSuit)
    val advisor = Advisor(seen)

    println("Hand: $hand   (${hand.tileCount} tiles)")
    println("─".repeat(48))

    if (hand.tileCount % 3 == 2) {
        val advice = advisor.recommendDiscard(hand)
        when {
            advice.isWin -> println("🀄  WINNING HAND (和牌)!")
            advice.voidTilesHeld > 0 -> println("定缺: holding ${advice.voidTilesHeld} void-suit tile(s) — dump them first.")
            advice.isTenpai -> println("TENPAI (听牌) — one tile from winning.")
            else -> println("Shanten (向听): ${advice.shanten}")
        }
        println()
        println("Best discards:")
        advice.options.take(5).forEachIndexed { i, opt ->
            val shantenLabel = when (opt.resultingShanten) {
                Shanten.WIN -> "WIN"
                0 -> "tenpai"
                else -> "${opt.resultingShanten}-shanten"
            }
            val accept = opt.acceptance
                .sortedByDescending { it.remaining }
                .joinToString(" ") { "${it.name}×${it.remaining}" }
            val tag = if (opt.forcedByVoid) " [定缺 forced]" else ""
            println("  ${i + 1}. discard ${opt.discardName.padEnd(4)} -> $shantenLabel" +
                "  (${opt.ukeireCount} tiles)$tag")
            if (accept.isNotEmpty()) println("        accepts: $accept")
        }
    } else {
        val s = advisor.shanten(hand)
        println(when (s) {
            Shanten.WIN -> "🀄  WINNING HAND (和牌)!"
            0 -> "TENPAI (听牌)."
            else -> "Shanten (向听): $s"
        })
        val acc = advisor.acceptance(hand).sortedByDescending { it.remaining }
        if (acc.isNotEmpty()) {
            val total = acc.sumOf { it.remaining }
            val list = acc.joinToString(" ") { "${it.name}×${it.remaining}" }
            println("Accepts ${acc.size} kinds / $total tiles: $list")
        }
    }
}
