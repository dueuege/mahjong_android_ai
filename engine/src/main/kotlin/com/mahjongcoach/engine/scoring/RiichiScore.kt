package com.mahjongcoach.engine.scoring

/** Situational facts the player must supply (can't be read off the tiles). */
data class RiichiContext(
    val winTile: Int,
    val tsumo: Boolean,
    val seatWind: Int = T34.EAST,    // your seat wind tile (27..30)
    val roundWind: Int = T34.EAST,   // prevailing wind tile (27..30)
    val isDealer: Boolean = seatWind == T34.EAST,
    val riichi: Boolean = false,
    val doubleRiichi: Boolean = false,
    val ippatsu: Boolean = false,
    val haitei: Boolean = false,     // win on the last drawable tile (tsumo)
    val houtei: Boolean = false,     // win on the last discard (ron)
    val rinshan: Boolean = false,    // win on the dead-wall replacement after a kan
    val chankan: Boolean = false,    // robbing a kan
    val doraIndicators: List<Int> = emptyList(),
    val uraIndicators: List<Int> = emptyList(),
    val akaDora: Int = 0,            // count of red-5 tiles in the hand
)

data class Yaku(val name: String, val han: Int)

data class RiichiResult(
    val win: Boolean,
    val noYaku: Boolean,
    val yaku: List<Yaku>,
    val han: Int,
    val fu: Int,
    val dora: Int,
    val yakumanUnits: Int,
    val payment: Payment,
    val note: String = "",
) {
    override fun toString(): String = when {
        !win -> "not a winning hand"
        noYaku -> "complete but 役なし (no yaku) — cannot win"
        yakumanUnits > 0 -> "${"★".repeat(yakumanUnits)} 役満 ×$yakumanUnits  → $payment\n  " +
            yaku.joinToString(" · ") { it.name }
        else -> "$han han / $fu fu  → $payment\n  " +
            (yaku + listOfNotNull(if (dora > 0) Yaku("dora", dora) else null))
                .joinToString(" · ") { "${it.name} ${it.han}" }
    }
}

/** Payment breakdown. For tsumo, the per-player figures; for ron, [ronPay]. */
data class Payment(
    val total: Int,
    val ronPay: Int = 0,
    val tsumoFromDealer: Int = 0,
    val tsumoFromEachNonDealer: Int = 0,
    val isTsumo: Boolean,
) {
    override fun toString(): String = when {
        !isTsumo -> "$total (ron)"
        tsumoFromDealer > 0 -> "$total ($tsumoFromEachNonDealer from each, $tsumoFromDealer from dealer)"
        else -> "$total ($tsumoFromEachNonDealer all)"
    }
}

/**
 * Japanese riichi scoring for a REVEALED winning hand. Covers the standard yaku,
 * fu, dora/ura/aka, and the full han·fu→points table including limit hands and a
 * range of yakuman. A few rare yaku (chuuren, kokushi-13-wait double, etc.) are
 * intentionally out of scope and noted as TODO.
 */
object RiichiScore {

    fun score(concealed: IntArray, melds: List<Group>, ctx: RiichiContext): RiichiResult {
        val menzen = melds.none { it.open }

        // Kokushi musou (thirteen orphans) — special shape, no group parse.
        if (melds.isEmpty() && WinDecomposer.isKokushi(concealed)) {
            return yakumanResult(listOf(Yaku("国士無双", 13)), 1, ctx)
        }

        val parses = WinDecomposer.decompose(concealed, melds)
        if (parses.isEmpty()) {
            return RiichiResult(false, false, emptyList(), 0, 0, 0, 0, zeroPay(ctx))
        }

        val full = fullCounts(concealed, melds)
        var best: RiichiResult? = null
        for (parse in parses) {
            val r = scoreParse(parse, full, melds, menzen, ctx)
            if (best == null || better(r, best)) best = r
        }
        return best!!
    }

    private fun better(a: RiichiResult, b: RiichiResult): Boolean {
        if (a.yakumanUnits != b.yakumanUnits) return a.yakumanUnits > b.yakumanUnits
        return a.payment.total > b.payment.total
    }

    private fun scoreParse(
        parse: Parse, full: IntArray, melds: List<Group>, menzen: Boolean, ctx: RiichiContext,
    ): RiichiResult {
        // ---- Yakuman first (they don't combine with normal yaku) ----
        val yakuman = detectYakuman(parse, full, ctx)
        if (yakuman.isNotEmpty()) {
            return yakumanResult(yakuman, yakuman.sumOf { it.han / 13 }.coerceAtLeast(yakuman.size), ctx)
        }

        val yaku = ArrayList<Yaku>()
        // ---- Context yaku ----
        if (ctx.doubleRiichi) yaku.add(Yaku("ダブル立直", 2))
        else if (ctx.riichi) yaku.add(Yaku("立直", 1))
        if (ctx.ippatsu) yaku.add(Yaku("一発", 1))
        if (menzen && ctx.tsumo) yaku.add(Yaku("門前清自摸和", 1))
        if (ctx.haitei) yaku.add(Yaku("海底摸月", 1))
        if (ctx.houtei) yaku.add(Yaku("河底撈魚", 1))
        if (ctx.rinshan) yaku.add(Yaku("嶺上開花", 1))
        if (ctx.chankan) yaku.add(Yaku("槍槓", 1))

        // ---- Hand-shape yaku ----
        yaku += detectHandYaku(parse, full, menzen, ctx)

        val dora = countDora(full, ctx)
        val yakuHan = yaku.sumOf { it.han }

        if (yakuHan == 0) {
            // Complete but no yaku — dora alone can't win.
            return RiichiResult(true, true, emptyList(), dora, 0, dora, 0, zeroPay(ctx), "no yaku")
        }

        val fu = computeFu(parse, menzen, ctx, yaku)
        val han = yakuHan + dora
        val payment = scoreFromHanFu(han, fu, ctx.isDealer, ctx.tsumo)
        return RiichiResult(true, false, yaku, han, fu, dora, 0, payment)
    }

    // ---------------------------------------------------------------- yaku ----

    private fun detectHandYaku(parse: Parse, full: IntArray, menzen: Boolean, ctx: RiichiContext): List<Yaku> {
        val out = ArrayList<Yaku>()
        val sets = parse.sets
        val seqs = sets.filter { it.type == GroupType.SEQUENCE }
        val trips = sets.filter { it.type == GroupType.TRIPLET || it.type == GroupType.KAN }

        if (parse.chiitoitsu) out.add(Yaku("七対子", 2))

        // Tanyao — no terminals/honors anywhere.
        if ((0 until T34.KINDS).none { full[it] > 0 && T34.isTerminalOrHonor(it) }) out.add(Yaku("断幺九", 1))

        // Yakuhai — dragon / round wind / seat wind triplets.
        for (t in trips) {
            if (T34.isDragon(t.tile)) out.add(Yaku("役牌 ${T34.name(t.tile)}", 1))
            if (t.tile == ctx.roundWind) out.add(Yaku("場風", 1))
            if (t.tile == ctx.seatWind) out.add(Yaku("自風", 1))
        }

        // Pinfu — closed, all sequences, non-yakuhai pair, ryanmen wait.
        if (menzen && !parse.chiitoitsu && seqs.size == 4) {
            val pairT = parse.pair.tile
            val pairIsYakuhai = T34.isDragon(pairT) || pairT == ctx.roundWind || pairT == ctx.seatWind
            if (!pairIsYakuhai && isRyanmenWin(parse, ctx.winTile)) out.add(Yaku("平和", 1))
        }

        // Iipeikou / Ryanpeikou — identical sequences (closed only).
        if (menzen && !parse.chiitoitsu) {
            val dup = seqs.groupingBy { it.tile }.eachCount()
            val pairs = dup.values.count { it >= 2 }
            when {
                pairs >= 2 -> out.add(Yaku("二盃口", 3))
                pairs == 1 -> out.add(Yaku("一盃口", 1))
            }
        }

        // Sanshoku doujun — same sequence in all three suits.
        if (seqs.isNotEmpty()) {
            val byRank = seqs.groupBy { T34.rankOf(it.tile) }
            if (byRank.any { (_, g) -> g.map { T34.suitOf(it.tile) }.toSet() == setOf(0, 1, 2) })
                out.add(Yaku("三色同順", if (menzen) 2 else 1))
        }
        // Sanshoku doukou — same triplet in all three suits.
        run {
            val byRank = trips.filter { it.tile < 27 }.groupBy { T34.rankOf(it.tile) }
            if (byRank.any { (_, g) -> g.map { T34.suitOf(it.tile) }.toSet() == setOf(0, 1, 2) })
                out.add(Yaku("三色同刻", 2))
        }
        // Ittsu — 123/456/789 in one suit.
        for (s in 0..2) {
            val starts = seqs.filter { T34.suitOf(it.tile) == s }.map { T34.rankOf(it.tile) }.toSet()
            if (starts.containsAll(listOf(1, 4, 7))) { out.add(Yaku("一気通貫", if (menzen) 2 else 1)); break }
        }

        // Toitoi / Sanankou / Sankantsu.
        if (!parse.chiitoitsu && trips.size == 4) out.add(Yaku("対々和", 2))
        val ankou = trips.count { it.isConcealed && !completedByRon(it, ctx) }
        if (ankou >= 3) out.add(Yaku("三暗刻", 2))
        if (trips.count { it.type == GroupType.KAN } >= 3) out.add(Yaku("三槓子", 2))

        // Chanta / Junchan — every group touches a terminal/honor.
        if (!parse.chiitoitsu) {
            val allTouch = parse.groups.all { groupHasTerminalOrHonor(it) }
            if (allTouch) {
                val anyHonor = parse.groups.any { T34.isHonor(it.tile) }
                if (anyHonor) out.add(Yaku("混全帯幺九", if (menzen) 2 else 1))
                else out.add(Yaku("純全帯幺九", if (menzen) 3 else 2))
            }
        }
        // Honroutou — only terminals and honors (with toitoi/chiitoi shape).
        if ((0 until T34.KINDS).all { full[it] == 0 || T34.isTerminalOrHonor(it) } &&
            (seqs.isEmpty())) out.add(Yaku("混老頭", 2))

        // Shousangen — two dragon triplets + dragon pair.
        run {
            val dragonTrips = trips.count { T34.isDragon(it.tile) }
            if (dragonTrips == 2 && T34.isDragon(parse.pair.tile)) out.add(Yaku("小三元", 2))
        }

        // Honitsu / Chinitsu.
        val numberSuits = (0..2).filter { s -> (s * 9 until s * 9 + 9).any { full[it] > 0 } }
        val hasHonor = (27..33).any { full[it] > 0 }
        if (numberSuits.size == 1) {
            if (!hasHonor) out.add(Yaku("清一色", if (menzen) 6 else 5))
            else out.add(Yaku("混一色", if (menzen) 3 else 2))
        }
        return out
    }

    private fun detectYakuman(parse: Parse, full: IntArray, ctx: RiichiContext): List<Yaku> {
        val out = ArrayList<Yaku>()
        val trips = parse.sets.filter { it.type == GroupType.TRIPLET || it.type == GroupType.KAN }

        val ankou = trips.count { it.isConcealed && !completedByRon(it, ctx) }
        // Suuankou — four concealed triplets (tanki pair-wait counts; ron-completed doesn't).
        if (!parse.chiitoitsu && trips.size == 4 && ankou == 4) out.add(Yaku("四暗刻", 13))

        val dragonTrips = trips.count { T34.isDragon(it.tile) }
        if (dragonTrips == 3) out.add(Yaku("大三元", 13))

        val windTrips = trips.count { T34.isWind(it.tile) }
        if (windTrips == 4) out.add(Yaku("大四喜", 13))
        else if (windTrips == 3 && T34.isWind(parse.pair.tile)) out.add(Yaku("小四喜", 13))

        if ((0 until T34.KINDS).all { full[it] == 0 || T34.isHonor(it) }) out.add(Yaku("字一色", 13))
        if ((0 until T34.KINDS).all { full[it] == 0 || T34.isTerminal(it) }) out.add(Yaku("清老頭", 13))
        if (trips.count { it.type == GroupType.KAN } == 4) out.add(Yaku("四槓子", 13))

        val green = setOf(19, 20, 21, 23, 25, T34.HATSU) // 2,3,4,6,8 sou + 發
        if ((0 until T34.KINDS).all { full[it] == 0 || it in green }) out.add(Yaku("緑一色", 13))

        return out
    }

    // ----------------------------------------------------------------- fu ----

    private fun computeFu(parse: Parse, menzen: Boolean, ctx: RiichiContext, yaku: List<Yaku>): Int {
        if (parse.chiitoitsu) return 25
        val pinfu = yaku.any { it.name == "平和" }
        if (pinfu) return if (ctx.tsumo) 20 else 30

        var fu = 20
        if (menzen && !ctx.tsumo) fu += 10            // menzen ron
        if (ctx.tsumo) fu += 2                        // tsumo (pinfu handled above)

        // Pair value.
        val p = parse.pair.tile
        if (T34.isDragon(p)) fu += 2
        if (p == ctx.roundWind) fu += 2
        if (p == ctx.seatWind) fu += 2

        // Triplets / kans.
        for (g in parse.sets) {
            if (g.type == GroupType.SEQUENCE) continue
            val toh = T34.isTerminalOrHonor(g.tile)
            val open = g.open || completedByRon(g, ctx)
            fu += when (g.type) {
                GroupType.KAN -> if (open) (if (toh) 16 else 8) else (if (toh) 32 else 16)
                else -> if (open) (if (toh) 4 else 2) else (if (toh) 8 else 4)
            }
        }
        // Wait fu.
        fu += waitFu(parse, ctx.winTile)

        // Open hand with no extra fu => 20+wait would be <30: round, but kuipinfu = 30.
        val rounded = ((fu + 9) / 10) * 10
        return if (!menzen && rounded == 20) 30 else rounded
    }

    private fun waitFu(parse: Parse, winTile: Int): Int {
        var fu = 0
        if (parse.pair.tile == winTile) fu = maxOf(fu, 2) // tanki
        for (g in parse.sets) {
            if (g.type != GroupType.SEQUENCE) continue
            if (winTile !in g.tiles) continue
            val r = T34.rankOf(g.tile)
            val w = T34.rankOf(winTile)
            when {
                w == r + 1 -> fu = maxOf(fu, 2)              // kanchan
                w == r + 2 && r == 1 -> fu = maxOf(fu, 2)    // penchan 12_3
                w == r && r + 2 == 9 -> fu = maxOf(fu, 2)    // penchan _789 waiting 7
                // ryanmen -> 0
            }
        }
        return fu
    }

    private fun isRyanmenWin(parse: Parse, winTile: Int): Boolean {
        if (parse.pair.tile == winTile) return false
        for (g in parse.sets) {
            if (g.type != GroupType.SEQUENCE || winTile !in g.tiles) continue
            val r = T34.rankOf(g.tile); val w = T34.rankOf(winTile)
            val penchanOrKanchan = w == r + 1 || (w == r + 2 && r == 1) || (w == r && r + 2 == 9)
            if (!penchanOrKanchan) return true
        }
        return false
    }

    private fun completedByRon(g: Group, ctx: RiichiContext): Boolean =
        !ctx.tsumo && g.isConcealed && (ctx.winTile in g.tiles) &&
            (g.type == GroupType.TRIPLET) && g.tile == ctx.winTile

    private fun groupHasTerminalOrHonor(g: Group): Boolean =
        g.tiles.any { T34.isTerminalOrHonor(it) }

    // -------------------------------------------------------------- scoring ----

    private fun countDora(full: IntArray, ctx: RiichiContext): Int {
        var n = ctx.akaDora
        for (ind in ctx.doraIndicators) n += full[doraFromIndicator(ind)]
        for (ind in ctx.uraIndicators) n += full[doraFromIndicator(ind)]
        return n
    }

    /** The dora is the tile AFTER the indicator, wrapping within its group. */
    fun doraFromIndicator(ind: Int): Int = when {
        ind < 27 -> { val r = T34.rankOf(ind); val base = (ind / 9) * 9; base + (r % 9) }
        ind in 27..30 -> 27 + (ind - 27 + 1) % 4        // winds cycle E→S→W→N→E
        else -> 31 + (ind - 31 + 1) % 3                 // dragons cycle 白→發→中→白
    }

    /** Standard han·fu → base-points table with limit hands. */
    fun scoreFromHanFu(han: Int, fu: Int, dealer: Boolean, tsumo: Boolean): Payment {
        val base = when {
            han >= 13 -> 8000
            han >= 11 -> 6000
            han >= 8 -> 4000
            han >= 6 -> 3000
            han == 5 -> 2000
            else -> minOf(2000, fu * (1 shl (2 + han)))  // cap at mangan
        }
        return payout(base, dealer, tsumo)
    }

    private fun payout(base: Int, dealer: Boolean, tsumo: Boolean): Payment {
        fun up(x: Int) = ((x + 99) / 100) * 100
        return if (!tsumo) {
            val pay = up(base * if (dealer) 6 else 4)
            Payment(pay, ronPay = pay, isTsumo = false)
        } else if (dealer) {
            val each = up(base * 2)
            Payment(each * 3, tsumoFromEachNonDealer = each, isTsumo = true)
        } else {
            val nd = up(base); val d = up(base * 2)
            Payment(nd * 2 + d, tsumoFromDealer = d, tsumoFromEachNonDealer = nd, isTsumo = true)
        }
    }

    private fun yakumanResult(yaku: List<Yaku>, units: Int, ctx: RiichiContext): RiichiResult {
        val payment = payout(8000 * units, ctx.isDealer, ctx.tsumo)
        return RiichiResult(true, false, yaku, 13 * units, 0, 0, units, payment)
    }

    private fun zeroPay(ctx: RiichiContext) = Payment(0, isTsumo = ctx.tsumo)

    private fun fullCounts(concealed: IntArray, melds: List<Group>): IntArray {
        val c = concealed.copyOf()
        for (m in melds) for (t in m.tiles) c[t]++
        return c
    }
}
