package com.mahjongcoach.engine

import com.mahjongcoach.engine.scoring.RiichiContext
import com.mahjongcoach.engine.scoring.RiichiScore
import com.mahjongcoach.engine.scoring.Ruleset
import com.mahjongcoach.engine.scoring.ScoreService
import com.mahjongcoach.engine.scoring.SichuanScore
import com.mahjongcoach.engine.scoring.T34
import com.mahjongcoach.engine.vision.RecognitionSmoother

/**
 * Standalone test runner (no JUnit/Gradle needed):
 *   kotlinc <sources> -include-runtime -d engine.jar
 *   java -cp engine.jar com.mahjongcoach.engine.TestsKt
 *
 * Each check prints PASS/FAIL; the process exits non-zero if anything fails.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name${if (detail.isNotEmpty()) "  -> $detail" else ""}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "got=$actual expected=$expected")

fun main() {
    println("== Tile parsing ==")
    run {
        val c = Tiles.parse("123m 456p 7799s")
        eq("parse total", Tiles.totalTiles(c), 10)
        eq("parse 1m", c[Tiles.index(Suit.MAN, 1)], 1)
        eq("parse 7s", c[Tiles.index(Suit.SOU, 7)], 2)
        eq("parse 9s", c[Tiles.index(Suit.SOU, 9)], 2)
        eq("round-trip", Tiles.toNotation(c), "123m456p7799s")
        eq("cnName 5p", Tiles.cnName(Tiles.index(Suit.PIN, 5)), "5筒")
    }

    println("== Standard shanten ==")
    run {
        // 4 sequences + pair = complete win.
        eq("win: 4 seq + pair", Shanten.standard(Tiles.parse("123m456m789m123p99p"), 0), Shanten.WIN)
        // 4 triplets + pair = complete win.
        eq("win: 4 trip + pair", Shanten.standard(Tiles.parse("111m222m333m444m55m"), 0), Shanten.WIN)
        // Tenpai waiting on 3p to finish 12p (pair fixed at 99p).
        eq("tenpai: ryanmen", Shanten.standard(Tiles.parse("123m456m789m12p99p"), 0), 0)
        // Tanki tenpai: 4 triplets, waiting to pair the float.
        eq("tenpai: tanki", Shanten.standard(Tiles.parse("111m222m333m444m5m"), 0), 0)
        // 3 melds + 2 disjoint kanchan, NO pair -> 1-shanten (block cap forbids 5 melds).
        eq("1-shanten: no pair", Shanten.standard(Tiles.parse("123m456m789m1357p"), 0), 1)
        // Open hand: two called melds, concealed 1 seq + 1 partial + pair -> tenpai.
        val twoMelds = listOf(Meld(MeldType.PON, Tiles.index(Suit.MAN, 1)), Meld(MeldType.PON, Tiles.index(Suit.MAN, 9)))
        eq("open tenpai (2 melds)", Shanten.standard(Tiles.parse("123p12s99s"), twoMelds.size), 0)
    }

    println("== Seven pairs (七对) ==")
    run {
        eq("7 pairs win", Shanten.sevenPairs(Tiles.parse("1133m5577p2244s9s9s")), Shanten.WIN)
        eq("6 pairs tenpai", Shanten.sevenPairs(Tiles.parse("1133m5577p2244s9s")), 0)
        // A triplet wastes a copy: only counts as one pair, kinds may be short.
        eq("triplet not 2 pairs", Shanten.sevenPairs(Tiles.parse("111m")), 6 - 1 + maxOf(0, 7 - 1))
        // Engine picks seven-pairs over standard when it's closer.
        eq("min picks chiitoi", Shanten.shanten(Hand.of("1133m5577p2244s9s")), 0)
    }

    println("== 定缺 void-suit handling ==")
    run {
        // Void = SOU but holding a 5s: the 5s must be dumped first.
        val hand = Hand.of("123m456m789m11p99p5s", voidSuit = Suit.SOU)
        eq("voidTilesHeld", hand.voidTilesHeld(), 1)
        val advisor = Advisor()
        val advice = advisor.recommendDiscard(hand)
        eq("forced discard is 5s", advice.best!!.discard, Tiles.index(Suit.SOU, 5))
        check("flagged forcedByVoid", advice.best!!.forcedByVoid)
        // After dumping 5s the hand is tenpai (3 melds + 2 pairs, shanpon wait).
        eq("tenpai after void dump", advice.best!!.resultingShanten, 0)
        // Acceptance must never include void-suit tiles.
        val afterVoidClear = Hand.of("123m456m789m11p99p", voidSuit = Suit.SOU)
        val acc = advisor.acceptance(afterVoidClear)
        check("no sou in acceptance", acc.none { Tiles.suitOf(it.tile) == Suit.SOU })
        eq("shanpon waits 1p/9p", acc.map { it.tile }.toSet(),
            setOf(Tiles.index(Suit.PIN, 1), Tiles.index(Suit.PIN, 9)))
    }

    println("== Best-discard recommendation ==")
    run {
        val advisor = Advisor()
        // 14 tiles, no void: 123m456m789m 1199p + a floating 5s. Best is to ditch
        // the lone 5s, keeping a shanpon tenpai on 1p/9p.
        val hand = Hand.of("123m456m789m1199p5s")
        val advice = advisor.recommendDiscard(hand)
        eq("current shanten", advice.shanten, 0)
        eq("best discard = 5s float", advice.best!!.discard, Tiles.index(Suit.SOU, 5))
        eq("stays tenpai", advice.best!!.resultingShanten, 0)
        check("ukeire counted", advice.best!!.ukeireCount > 0)
        // 'seen' reduces remaining copies: if two 1p already visible, ukeire shrinks.
        val seen = IntArray(Tiles.TILE_KINDS)
        seen[Tiles.index(Suit.PIN, 1)] = 2
        val advisor2 = Advisor(seen)
        val acc2 = advisor2.acceptance(Hand.of("123m456m789m1199p"))
        // Hold 2 copies of 1p, 2 already seen -> 0 remaining, so 1p drops out entirely
        // while 9p (4 - 2 held - 0 seen = 2) survives.
        check("seen drops exhausted 1p", acc2.none { it.tile == Tiles.index(Suit.PIN, 1) })
        eq("9p still 2 remaining",
            acc2.first { it.tile == Tiles.index(Suit.PIN, 9) }.remaining, 2)
    }

    println("== Speech parsing (audio board path) ==")
    run {
        val ev = SpeechParser.parse("三万")
        eq("CN tile 三万", ev, listOf(SpeechParser.Event.TileSpoken(Tiles.index(Suit.MAN, 3))))
        eq("digit+suit 5条", SpeechParser.parse("打5条"),
            listOf(SpeechParser.Event.TileSpoken(Tiles.index(Suit.SOU, 5))))
        eq("alias 索=条", SpeechParser.parse("七索"),
            listOf(SpeechParser.Event.TileSpoken(Tiles.index(Suit.SOU, 7))))
        // A sequence of calls in one utterance.
        val seq = SpeechParser.parse("我碰三万，他打八筒，胡了")
        check("detects pon", seq.any { it is SpeechParser.Event.Pon })
        check("detects win", seq.any { it is SpeechParser.Event.Win })
        eq("pon attaches tile", (seq.first { it is SpeechParser.Event.Pon } as SpeechParser.Event.Pon).tile,
            Tiles.index(Suit.MAN, 3))
        // spokenTileCounts feeds the advisor's "seen" array.
        val counts = SpeechParser.spokenTileCounts("一万 一万 九条")
        eq("counts 1m", counts[Tiles.index(Suit.MAN, 1)], 2)
        eq("counts 9s", counts[Tiles.index(Suit.SOU, 9)], 1)
    }

    println("== Sichuan scoring (番/倍) ==")
    run {
        eq("平胡 ×1", SichuanScore.scoreNotation("123m456m789m123p99p").multiplier, 1)
        eq("碰碰胡 ×2", SichuanScore.scoreNotation("111m333m555p777s99m").multiplier, 2)
        eq("清一色 ×4", SichuanScore.scoreNotation("123m456m789m234m99m").multiplier, 4)
        eq("清碰 ×8", SichuanScore.scoreNotation("111m222m333m444m99m").multiplier, 8)
        eq("七对 ×4", SichuanScore.scoreNotation("1122m3344p5566s99s").multiplier, 4)
        eq("龙七对 ×8", SichuanScore.scoreNotation("1111m22m33m44p55p66p").multiplier, 8)
        eq("清七对 ×16", SichuanScore.scoreNotation("11223344556677p").multiplier, 16)
        val gen = SichuanScore.score(T34.parse("1111m222m333m444m99p"), kans = listOf(0))
        eq("碰碰胡+根(杠) ×4", gen.multiplier, 4)
        eq("根 count", gen.gen, 1)
        check("rejects honors", !SichuanScore.score(T34.parse("111z456m789m123p99p")).win)
    }

    println("== Japanese scoring: han·fu → points table ==")
    run {
        eq("3han40fu nd ron = 5200", RiichiScore.scoreFromHanFu(3, 40, false, false).total, 5200)
        eq("4han30fu nd ron = 7700", RiichiScore.scoreFromHanFu(4, 30, false, false).total, 7700)
        eq("5han mangan nd = 8000", RiichiScore.scoreFromHanFu(5, 30, false, false).total, 8000)
        eq("3han30fu dealer ron = 5800", RiichiScore.scoreFromHanFu(3, 30, true, false).total, 5800)
        val t = RiichiScore.scoreFromHanFu(2, 30, false, true)
        eq("2han30fu nd tsumo total", t.total, 2000)
        eq("  ...from each nd", t.tsumoFromEachNonDealer, 500)
        eq("  ...from dealer", t.tsumoFromDealer, 1000)
        eq("haneman dealer ron", RiichiScore.scoreFromHanFu(6, 30, true, false).total, 18000)
    }

    println("== Japanese scoring: full hands ==")
    run {
        // Pinfu + tanyao, closed, non-dealer ron on 4m (ryanmen) = 2 han / 30 fu = 2000.
        val pinfu = RiichiScore.score(
            T34.parse("234m567m345p678p55s"), emptyList(),
            RiichiContext(winTile = 3, tsumo = false, seatWind = T34.SOUTH, roundWind = T34.EAST),
        )
        check("pinfu+tanyao is a win", pinfu.win && !pinfu.noYaku)
        eq("pinfu han", pinfu.han, 2)
        eq("pinfu fu", pinfu.fu, 30)
        eq("pinfu points", pinfu.payment.total, 2000)
        check("has 平和", pinfu.yaku.any { it.name == "平和" })
        check("has 断幺九", pinfu.yaku.any { it.name == "断幺九" })

        // Chiitoitsu (non-tanyao) closed ron = 2 han / 25 fu = 1600.
        val chiitoi = RiichiScore.score(
            T34.parse("1199m2233p4455s7z7z"), emptyList(),
            RiichiContext(winTile = T34.parse("7z").indexOfFirst { it > 0 }, tsumo = false,
                seatWind = T34.SOUTH, roundWind = T34.EAST),
        )
        eq("chiitoi fu", chiitoi.fu, 25)
        eq("chiitoi han", chiitoi.han, 2)
        eq("chiitoi points", chiitoi.payment.total, 1600)

        // Kokushi musou — yakuman, non-dealer ron = 32000.
        val kokushi = RiichiScore.score(
            T34.parse("119m19p19s1234567z"), emptyList(),
            RiichiContext(winTile = 0, tsumo = false, seatWind = T34.SOUTH, roundWind = T34.EAST),
        )
        eq("kokushi yakuman units", kokushi.yakumanUnits, 1)
        eq("kokushi points", kokushi.payment.total, 32000)

        // Honitsu (man + honors), closed.
        val honitsu = RiichiScore.score(
            T34.parse("234m234m567m99m111z"), emptyList(),
            RiichiContext(winTile = 1, tsumo = false, seatWind = T34.EAST, roundWind = T34.EAST),
        )
        check("detects 混一色", honitsu.win && honitsu.yaku.any { it.name == "混一色" })

        // Complete but no yaku (open tanyao-less, no yakuhai) -> cannot win.
        val noyaku = RiichiScore.score(
            T34.parse("123m456m789p99s"),
            listOf(com.mahjongcoach.engine.scoring.Group(
                com.mahjongcoach.engine.scoring.GroupType.SEQUENCE, T34.parse("1p").indexOfFirst { it > 0 }, open = true)),
            RiichiContext(winTile = 0, tsumo = false, seatWind = T34.SOUTH, roundWind = T34.EAST),
        )
        check("no-yaku flagged", noyaku.win && noyaku.noYaku)
    }

    println("== Recognition smoothing (camera path) ==")
    run {
        val sm = RecognitionSmoother(window = 5)
        repeat(3) { sm.submit(Tiles.parse("111m22p")) }
        sm.submit(Tiles.parse("11m22p"))            // one noisy frame: a 1m flickered out
        val stable = sm.submit(Tiles.parse("111m22p"))
        eq("median rejects flicker", stable[Tiles.index(Suit.MAN, 1)], 3)
        eq("median keeps stable tile", stable[Tiles.index(Suit.PIN, 2)], 2)
        check("window reports stable", sm.isStable())
    }

    println("== ScoreService bridge (photo → score) ==")
    run {
        eq("Sichuan via service", ScoreService.scoreSichuan(T34.parse("123m456m789m123p99p")).multiplier, 1)
        val desc = ScoreService.describe(
            Ruleset.JAPANESE, T34.parse("234m567m345p678p55s"),
            ctx = RiichiContext(winTile = 3, tsumo = false, seatWind = T34.SOUTH, roundWind = T34.EAST),
        )
        check("Japanese describe mentions han", desc.contains("han"))
    }

    println("== Analysis layer (superhuman coach) ==")
    run {
        // EV ranking should prefer the higher-VALUE route over the merely
        // faster one. A hand one tile from BOTH a flush (清一色 ×4) and a cheap
        // mixed shape should rank the flush-preserving discard's EV higher.
        // Sanity: EV of a tenpai 清一色 hand reflects the ×4 multiplier.
        val flush = Hand.of("1112345678999m")           // pure-man, tenpai (9-sided)
        val ev = com.mahjongcoach.engine.analysis.DiscardEV.rank(flush)
        check("EV ranks something", ev.isNotEmpty())
        check("EV values the 清一色 route (>1×)", ev.first().valueX > 1.5,
            "valueX=${ev.firstOrNull()?.valueX}")

        // Situation triage classifies the four cases.
        val newg = Hand.of("123459m238p5689s")          // 13 tiles, no void
        check("new game classified",
            com.mahjongcoach.engine.analysis.Situation.classify(newg, 3) ==
                com.mahjongcoach.engine.analysis.GamePhase.NEW_GAME)
        check("defense on strong threat",
            com.mahjongcoach.engine.analysis.Situation.classify(flush, 0, threatLevel = 2) ==
                com.mahjongcoach.engine.analysis.GamePhase.DEFENSE)

        // CoachAnalysis produces a one-line call + detail.
        val rep = com.mahjongcoach.engine.analysis.CoachAnalysis.analyze(Hand.of("123m456m789m1199p5s", voidSuit = Suit.SOU))
        check("analysis one-line non-empty", rep.oneLine.isNotBlank())
        check("analysis detail non-empty", rep.detail.isNotBlank())
        val newRep = com.mahjongcoach.engine.analysis.CoachAnalysis.analyze(newg)
        check("new-game analysis suggests 定缺", newRep.oneLine.contains("定缺"))

        // Danger: genbutsu (discarded) safer than an undiscarded middle tile;
        // terminals safer than middles.
        val dhand = Hand.of("123456789m1234p")          // holds 5m (middle) & 1p, 9.. etc
        val seenPile = IntArray(Tiles.TILE_KINDS).also { it[Tiles.index(Suit.MAN, 5)] = 1 } // 5m discarded
        val dr = com.mahjongcoach.engine.analysis.Danger.rank(dhand, seenPile).associate { it.tile to it.danger }
        val d5m = dr[Tiles.index(Suit.MAN, 5)] ?: 1.0   // genbutsu
        val d4m = dr[Tiles.index(Suit.MAN, 4)] ?: 0.0   // undiscarded middle
        check("genbutsu safer than live middle", d5m < d4m, "5m=$d5m 4m=$d4m")
        val d1m = dr[Tiles.index(Suit.MAN, 1)] ?: 1.0
        check("terminal safer than middle", d1m < d4m, "1m=$d1m 4m=$d4m")
        check("threat rises with pond size",
            com.mahjongcoach.engine.analysis.Danger.threatLevel(IntArray(27).also { it.fill(2) }) == 2)

        // WinRate: a tenpai hand should win more often than a 2-shanten one,
        // and probabilities stay in [0,1].
        val tenpai = Hand.of("123m456m789m1199p")       // 13 tiles, tenpai (shanpon 1p/9p)
        val far = Hand.of("159m159p159s1234m")          // 13 tiles, messy / far
        val wTen = com.mahjongcoach.engine.analysis.WinRate.estimate(tenpai, sims = 300).winProb
        val wFar = com.mahjongcoach.engine.analysis.WinRate.estimate(far, sims = 300).winProb
        check("winrate in [0,1]", wTen in 0.0..1.0 && wFar in 0.0..1.0, "ten=$wTen far=$wFar")
        check("tenpai wins more than far hand", wTen > wFar, "ten=$wTen far=$wFar")
    }

    println("== Assistant tool layer (LLM bridge) ==")
    run {
        check("four tools exposed", Assistant.tools.size == 4, "size=${Assistant.tools.size}")
        val an = Assistant.dispatch("coach_analysis", mapOf("hand" to "123m456m789m1199p5s", "void_suit" to "s"))
        check("coach_analysis returns a report", an.contains("EV") || an.contains("听牌") || an.contains("向听"))
        check("system prompt present", Assistant.SYSTEM_PROMPT.isNotBlank())
        val a = Assistant.dispatch("recommend_discard", mapOf("hand" to "123m456m789m1199p5s", "void_suit" to "s"))
        check("advise dumps void 5s", a.contains("5条"))
        check("advise reports tenpai", a.contains("tenpai"))
        val s = Assistant.dispatch("score_hand", mapOf("ruleset" to "sichuan", "hand" to "11223344556677p"))
        check("score sichuan 清七对", s.contains("清七对"))
        val j = Assistant.dispatch("score_hand", mapOf("ruleset" to "japan", "hand" to "234m567m345p678p55s", "win_tile" to "4m"))
        check("score japan has han", j.contains("han"))
        check("unknown tool handled", Assistant.dispatch("bogus", emptyMap()).contains("unknown tool"))
        check("missing hand handled", Assistant.dispatch("recommend_discard", emptyMap()).contains("error"))
    }

    println()
    println("RESULTS: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
