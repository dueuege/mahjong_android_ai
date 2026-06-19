package com.mahjongcoach.engine

/**
 * Turns a speech-to-text transcript of what your friends SAY at the table into
 * structured game events, so the board state can be reconstructed from audio
 * (your main board sensor for Sichuan, where players call tiles aloud).
 *
 * This is the public-information audio path: it only understands tiles/calls
 * that were announced out loud. It never infers anyone's concealed hand.
 *
 * Pure logic, fully testable. The Android layer feeds it ASR output.
 */
object SpeechParser {

    sealed interface Event {
        /** A specific tile was named, e.g. "三万" / "5条" / "wu tiao". */
        data class TileSpoken(val tile: Int) : Event
        /** A 碰 (pon) call. [tile] is the named tile if one was spoken nearby. */
        data class Pon(val tile: Int?) : Event
        /** A 杠 (kan) call. */
        data class Kan(val tile: Int?) : Event
        /** A win was declared (胡 / 和). */
        data object Win : Event
    }

    // Chinese + romanised numerals -> rank.
    private val numerals: Map<Char, Int> = buildMap {
        "一壹"  .forEach { put(it, 1) }
        "二两兩貳".forEach { put(it, 2) }
        "三叁參".forEach { put(it, 3) }
        "四肆"  .forEach { put(it, 4) }
        "五伍"  .forEach { put(it, 5) }
        "六陆陸".forEach { put(it, 6) }
        "七柒"  .forEach { put(it, 7) }
        "八捌"  .forEach { put(it, 8) }
        "九玖"  .forEach { put(it, 9) }
        ('1'..'9').forEach { put(it, it - '0') }
    }

    // Suit characters and common spoken aliases (万/萬, 筒/饼, 条/索).
    private val suitChars: Map<Char, Suit> = buildMap {
        "万萬".forEach { put(it, Suit.MAN) }
        "筒饼餅".forEach { put(it, Suit.PIN) }
        "条索條".forEach { put(it, Suit.SOU) }
    }

    private const val PON = "碰"
    private val kanWords = listOf("杠", "槓")
    private val winWords = listOf("胡", "糊", "和了")

    /**
     * Scan a transcript and emit events in order. A tile is recognised as a
     * numeral immediately followed (allowing the words "万/筒/条") by a suit
     * char. Call words (碰/杠/胡) are detected independently and attached to the
     * most recently spoken tile when one is adjacent.
     */
    fun parse(text: String): List<Event> {
        val events = ArrayList<Event>()
        var lastTile: Int? = null
        // A call word may be spoken before its tile ("碰三万"); remember a call
        // that is still waiting for the tile that follows it.
        var pendingCallIdx = -1
        var pendingIsKan = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val rank = numerals[ch]
            if (rank != null) {
                // Look ahead a couple of chars for a suit (handles "五条"/"5 条").
                var j = i + 1
                var suit: Suit? = null
                var look = 0
                while (j < text.length && look < 3) {
                    suitChars[text[j]]?.let { suit = it }
                    if (suit != null) break
                    if (numerals[text[j]] != null) break // next numeral, stop
                    j++; look++
                }
                val s = suit
                if (s != null) {
                    val tile = Tiles.index(s, rank)
                    if (pendingCallIdx >= 0) {
                        // Backfill the call word that preceded this tile.
                        events[pendingCallIdx] = if (pendingIsKan) Event.Kan(tile) else Event.Pon(tile)
                        pendingCallIdx = -1
                    }
                    events.add(Event.TileSpoken(tile))
                    lastTile = tile
                    i = j + 1
                    continue
                }
            }
            when {
                ch.toString() == PON -> {
                    if (lastTile != null) events.add(Event.Pon(lastTile))
                    else { events.add(Event.Pon(null)); pendingCallIdx = events.lastIndex; pendingIsKan = false }
                }
                kanWords.any { text.startsWith(it, i) } -> {
                    if (lastTile != null) events.add(Event.Kan(lastTile))
                    else { events.add(Event.Kan(null)); pendingCallIdx = events.lastIndex; pendingIsKan = true }
                }
                winWords.any { text.startsWith(it, i) } -> events.add(Event.Win)
            }
            i++
        }
        return events
    }

    /** Convenience: just the tiles mentioned, as a counts array (for `seen`). */
    fun spokenTileCounts(text: String): IntArray {
        val counts = IntArray(Tiles.TILE_KINDS)
        for (e in parse(text)) if (e is Event.TileSpoken) counts[e.tile]++
        return counts
    }
}
