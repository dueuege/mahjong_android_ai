package com.mahjongcoach.engine.analysis

import com.mahjongcoach.engine.Advisor
import com.mahjongcoach.engine.Hand
import com.mahjongcoach.engine.Shanten
import com.mahjongcoach.engine.Tiles

/**
 * The orchestrator: turns a hand + public table state into a compact analysis
 * the UI and the LLM both consume.
 *
 *  - [oneLine] — the deterministic call for the banner (instant, no LLM).
 *  - [detail]  — a structured report fed into the coach prompt so the LLM
 *    reasons over engine-computed numbers (EV, ukeire, route) rather than
 *    guessing.
 *
 * Only the analyses relevant to the [GamePhase] are computed/included, so it
 * stays fast and the advice stays focused (Phase-2 danger / Phase-3 win-rate
 * slot in here).
 */
data class AnalysisReport(
    val phase: GamePhase,
    val oneLine: String,
    val detail: String,
)

object CoachAnalysis {

    fun analyze(hand: Hand, seen: IntArray = IntArray(Tiles.TILE_KINDS)): AnalysisReport {
        val advisor = Advisor(seen)

        // NEW GAME — recommend 定缺 + the route.
        if (hand.tileCount == 13 && hand.melds.isEmpty() && hand.voidSuit == null) {
            val rec = advisor.recommendVoidSuit(hand)
            val c = advisor.suitCounts(hand)   // [man, pin, sou]
            val oneLine = "新局 · 建议定缺 ${rec?.cn ?: "?"}（最少 ${c.minOrNull() ?: 0} 张）"
            val detail = "新局未定缺。各门张数 万${c[0]} 筒${c[1]} 条${c[2]}。" +
                "建议定缺张数最少的 ${rec?.cn}，保留另两门凑顺子/刻子。"
            return AnalysisReport(GamePhase.NEW_GAME, oneLine, detail)
        }

        // 14-tile (post-draw) — EV discard ranking.
        if (hand.tileCount % 3 == 2) {
            val ranked = DiscardEV.rank(hand, seen)
            val best = ranked.firstOrNull()
                ?: return AnalysisReport(GamePhase.BUILDING, "无可用打牌建议", "无法解析手牌。")
            val phase = Situation.classify(hand, best.resultingShanten)
            val state = stateLabel(best.resultingShanten)
            val oneLine = "打${best.discardName} · $state · EV ${fmt(best.ev)}（估值×${fmt(best.valueX)}）"
            val detail = buildString {
                append("打牌候选（按 EV = 进张速度 × 牌型估值 排序）：\n")
                ranked.take(3).forEach {
                    append("· 打${it.discardName}: ${stateLabel(it.resultingShanten)}，")
                    append("进张 ${it.ukeire}，估值×${fmt(it.valueX)}，EV ${fmt(it.ev)}\n")
                }
                append("（估值越高表示该路线牌型越大，如清一色/碰碰胡；EV 兼顾速度与价值。）")
            }
            return AnalysisReport(phase, oneLine, detail)
        }

        // 13-tile (pre-draw / waiting) — shanten + acceptance.
        val shanten = advisor.shanten(hand)
        val acc = advisor.acceptance(hand).sortedByDescending { it.remaining }
        val phase = Situation.classify(hand, shanten)
        val state = stateLabel(shanten)
        val ukeire = acc.sumOf { it.remaining }
        val oneLine = "$state · 进张 $ukeire"
        val detail = "13 张待摸。$state。进张：" +
            acc.joinToString(" ") { "${it.name}(${it.remaining})" }
        return AnalysisReport(phase, oneLine, detail)
    }

    private fun stateLabel(shanten: Int): String = when {
        shanten == Shanten.WIN -> "和牌"
        shanten <= 0 -> "听牌"
        else -> "${shanten}向听"
    }

    private fun fmt(v: Double): String = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
}
