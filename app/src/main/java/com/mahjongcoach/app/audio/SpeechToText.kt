package com.mahjongcoach.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One-shot speech-to-text: tap to record a single utterance, get the final
 * transcript back. Used by the assistant's voice button — "one piece of audio →
 * ASR → LLM input". Distinct from [BoardAudioListener], which listens
 * continuously to reconstruct the public board.
 */
class SpeechToText(
    context: Context,
    private val languageTag: String = "zh-CN",
) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** Record one utterance. [onResult] gets the transcript; [onError] gets a message. */
    fun listenOnce(onResult: (String) -> Unit, onError: (String) -> Unit, onListening: () -> Unit = {}) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening()
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onError("Didn't catch that.") else onResult(text)
            }
            override fun onError(error: Int) = onError("Speech error ($error). Try again.")
            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    fun cancel() = recognizer.cancel()
    fun destroy() = recognizer.destroy()
}
