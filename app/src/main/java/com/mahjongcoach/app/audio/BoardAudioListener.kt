package com.mahjongcoach.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.mahjongcoach.engine.SpeechParser

/**
 * Reconstructs the PUBLIC board from what your friends SAY — your main board
 * sensor for Sichuan, where players call tiles aloud ("碰三万", "打八筒", "杠").
 *
 * It only ever processes spoken, public announcements. It does not and must not
 * try to infer anyone's concealed hand.
 *
 * Pipeline: Android SpeechRecognizer (continuous) -> transcript -> the pure,
 * unit-tested [SpeechParser] -> structured events -> [onEvents] (which the app
 * folds into GameState.observeSeen / meld tracking).
 *
 * NOTE: the platform SpeechRecognizer is single-utterance; production should
 * restart it on results/errors for continuous listening, or swap in an on-device
 * streaming recogniser (e.g. Vosk / whisper.cpp) for offline, low-latency use.
 */
class BoardAudioListener(
    context: Context,
    private val languageTag: String = "zh-CN",
    private val onEvents: (List<SpeechParser.Event>) -> Unit,
) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) = handle(results)
            override fun onPartialResults(partial: Bundle) = handle(partial)
            override fun onError(error: Int) { restart() }   // TODO: backoff on repeated errors
            override fun onEndOfSpeech() { restart() }        // keep listening continuously
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handle(bundle: Bundle) {
        val text = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull() ?: return
        val events = SpeechParser.parse(text)
        if (events.isNotEmpty()) onEvents(events)
    }

    fun start() = recognizer.startListening(intent)
    private fun restart() { recognizer.cancel(); recognizer.startListening(intent) }
    fun stop() = recognizer.stopListening()
    fun destroy() = recognizer.destroy()
}
