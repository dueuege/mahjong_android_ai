package com.mahjongcoach.app.llm

/**
 * On-device (edge) backend — offline, private, free per use. Not yet wired.
 *
 * Implementation plan (docs/LLM.md): load a small instruct model (e.g. Gemma /
 * Qwen 1.5–3B) via MediaPipe LLM Inference or llama.cpp, expose the same two
 * engine tools through the runtime's function-calling/grammar support, and run
 * the same tool loop as [ClaudeClient]. Quality at this size is lower, so this is
 * positioned as the offline fallback, not the default.
 *
 * The engine [com.mahjongcoach.engine.Assistant] tool layer is backend-agnostic,
 * so only this class needs to change to add edge support.
 */
class EdgeLlmClient : LlmClient {
    override val label = "On-device (edge)"
    override val available = false // flip once a model is bundled / downloaded
    override suspend fun reply(history: List<ChatTurn>): String =
        "On-device model isn't bundled yet. Use the Claude API backend in Settings, " +
            "or wait for the edge build (see docs/LLM.md)."
}
