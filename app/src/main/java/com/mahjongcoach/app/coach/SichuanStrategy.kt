package com.mahjongcoach.app.coach

/**
 * A condensed professional Sichuan-mahjong (血战到底) strategy "guide book" fed
 * to the LLM coach as context so its advice reflects strong play, not generic
 * mahjong. Kept compact (token budget) but covers the decisions that matter.
 *
 * The deterministic numbers (shanten, ukeire, remaining counts, 定缺
 * recommendation) still come from the engine and the [STATE] block — this is
 * the reasoning framework the model applies on top of them. A fuller
 * human-readable version lives in docs/SICHUAN_STRATEGY.md.
 */
object SichuanStrategy {
    val GUIDE: String = """
        四川麻将（血战到底）专业打法要点：

        【规则要点】只有万/筒/条三门，无字牌花牌。开局必须定缺一门；缺门牌不能
        碰杠胡，且必须先打光缺张才能打其它牌。血战到底：有人胡牌后牌局继续，直到
        三家胡或摸完，所以一局可有多个赢家，防守贯穿全局。流局时未听牌者要向听牌
        者赔“查叫/查大叫”，所以尽量听牌。

        【定缺】选你最弱的一门：张数最少、且多为孤张/边张、对另两门帮助最小的那门。
        留下的两门要尽量能凑成顺子或刻子。

        【牌型价值，从高到低尽量做】清一色（单一花色，番高）> 碰碰胡（全刻子）>
        七对/龙七对（含一组四张）> 平胡。根（任意四张相同，含杠）翻倍。杠还有
        刮风（明杠）/下雨（暗杠）额外分和补牌机会。能往大牌走且不拖慢听牌就走。

        【进张效率】两面搭子（如 34 等 2/5，共8张）优于 嵌张/边张（4张）优于
        对碰/单骑。打牌时优先保留进张多、活张多的形状；用每张的“剩余张数”判断：
        剩余少(带?表示牌池未知、为上限)说明该张稀缺，听这种牌成功率低，能换则换。

        【留牌口诀】
        - 金三银七：3 和 7 是最强的连接张（3 接 1-2-4-5，7 接 5-6-8-9），价值最高，
          轻易别拆；2 和 8 次之；1/9 幺九最弱，无搭子优先打。
        - 草肚皮：4、5、6 中张连接面最广（5 可入 345/456/567/567），最灵活，
          做顺子和清一色时尽量留中张、先弃边张。
        - 边张（12 听 3、89 听 7）和嵌张只 4 张进张，是“坏型”，能换成两面就换；
          被迫听边张时番要够大才值。

        【生张熟张（防守）】
        - 熟张=牌池里已出现过的、对家打过的，相对安全；生张=没见过的新张，最危险。
        - 没思路时打熟张、打缺门已定的对家用不上的张；逼到要防守时，优先现物>半熟>生张。

        【前期 vs 后期】
        - 前期（开局~摸几轮）：手牌灵活时往大牌走（清一色/碰碰胡/根），留好型；
        - 后期（牌墙将尽 / 有人听牌）：抢快听牌避免查叫，同时收紧防守、少打生张；
          分差大或对家明显大牌时，果断转防守、保不点炮。

        【攻防（血战特有）】因为胡后继续打，别只顾自己：
        - 留意已碰2副以上的对家（多半做碰碰胡或清一色），别点炮喂大牌；
        - 对家缺门已明，避免打他能用的另两门危险张；
        - 自己分差大时以听牌/不点炮为先，“大牌点小牌不亏”是错觉，赢才是硬道理；
        - 顺势而为：根据场上信息随时调整做牌方向，是高手与普通玩家的分水岭。

        【给建议时】先一句结论（定缺/该打哪张/是否听牌/弃和），再用 2–4 句解释，
        结合进张、剩余张数、牌型方向、上面的口诀与防守要点。口语、像牌桌旁的教练。
    """.trimIndent()
}
