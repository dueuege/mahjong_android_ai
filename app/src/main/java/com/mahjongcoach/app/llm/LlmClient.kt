package com.mahjongcoach.app.llm

import android.graphics.Bitmap

/** One turn of the assistant conversation. Tool turns are handled inside the client. */
enum class Role { USER, ASSISTANT }
data class ChatTurn(val role: Role, val text: String)

/**
 * Natural-language front-end over the engine. Implementations are swappable
 * (Claude API now, on-device edge later) and selected in Settings.
 *
 * Whatever the backend, the model is constrained to call the engine tools
 * (see engine `Assistant`) for any shanten/score/discard claim — it never does
 * mahjong math itself. The coach works fully without any LlmClient; this only
 * adds a conversational/voice layer on top.
 */
interface LlmClient {
    /** Human-readable backend label for the UI. */
    val label: String

    /** Whether this backend is ready to use (e.g. API key set, model downloaded). */
    val available: Boolean

    /** Produce the assistant's reply given the conversation so far (last turn = user). */
    suspend fun reply(history: List<ChatTurn>): String

    /**
     * Read the player's OWN hand from a still camera frame. Returns a 27-length
     * counts array indexed per `engine.Tiles` (`suit = i/9`, `rank = i%9+1`), or
     * null if the model couldn't read tiles (no vision support, low confidence,
     * empty frame). The Coach uses this only when the user opts into LLM vision
     * in Settings — the on-device recognizer is the default path.
     *
     * Same ethical boundary as everywhere else: this reads your own hand only;
     * never feed it opponents' concealed tiles. Default impl returns null so
     * non-vision backends (Disabled, Edge stub) keep compiling.
     */
    suspend fun recognizeHand(bitmap: Bitmap): IntArray? = null
}

/** Placeholder used when the user has the assistant turned off. */
object DisabledLlm : LlmClient {
    override val label = "off"
    override val available = false
    override suspend fun reply(history: List<ChatTurn>) =
        "The AI assistant is off. The coach still works — enable the assistant in Settings to chat or use voice."
}
