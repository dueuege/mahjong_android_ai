package com.mahjongcoach.app.coach

/**
 * Condensed professional Japanese riichi strategy "guide book" fed to the LLM
 * coach when the ruleset is Japanese. Parallels [SichuanStrategy]. The engine
 * still supplies shanten / ukeire / scoring; this is the reasoning framework.
 * Fuller human version in docs/RIICHI_STRATEGY.md.
 */
object RiichiStrategy {
    val GUIDE: String = """
        日本麻将（立直/Riichi）专业打法要点：

        【规则要点】34种牌：万/筒/索 1-9 + 字牌（东南西北 白发中）。和牌必须有
        “役”（至少一个番种），否则不能和（无役不能荣/自摸）。立直：门前清听牌时
        可宣告立直，押1000点，之后手牌固定。振听：自己舍过的牌不能荣和（含舍张里
        任一听牌张都不能荣，只能自摸）。

        【役与打点】常见役：立直、断幺九（tanyao，全2-8）、役牌（自风/场风/三元牌
        刻子）、平和（pinfu，全顺+两面听+无役牌雀头）、一气、三色、混一/清一色、
        七对子、对对、混老头等。番 + 符 → 点数；宝牌(dora)、赤5、里宝、一发加番。
        门前听牌优先考虑立直；副露(鸣牌)会失去门前番，需确保有役（如役牌、断幺、
        三色、一气）。

        【进张效率】两面(ryanmen，8张)优于 嵌张/边张(4张)优于 单骑/双碰。保留
        好型听牌、好型听牌优于差型。注意宝牌：留宝牌附近搭子能涨番。先做向听数最低、
        进张最多的形状；用每张剩余数(带?表示牌河信息不足、为上限)判断听牌质量。

        【防守】读牌河：
        - 有人立直/副露逼近时，若自己手牌差或听牌差，转防守(降听/弃和)；
        - 安全牌：现物(对方牌河里有的)、筋(suji，如对方打过4则1/7较安全)、
          壁(kabe，自己看到某张4枚则相关嵌张/边张不可能)；
        - 不要为小听牌去点炮大牌；ベタ降り(全力防守)在落后时是正解。

        【给建议时】先给结论（立直/该切哪张/做什么役/是否该转防守），再补一句原因
        （基于进张、剩余张数、番型方向、宝牌、振听与放铳风险）。简洁、口语。
    """.trimIndent()
}
