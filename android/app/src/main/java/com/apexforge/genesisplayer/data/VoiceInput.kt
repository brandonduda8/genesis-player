package com.apexforge.genesisplayer.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "GenesisPlayer"

/**
 * Voice input for the Apollo tab ONLY (APOLLO-LIVE.md §3).
 *
 * Push-to-talk mic button → on-device Android SpeechRecognizer → the SAME
 * intent path as text (input="voice"). Text does everything voice does.
 * Voice is input-only: no TTS, Apollo's replies stay text.
 *
 * Privacy: recognition happens on-device where the OS provides it; where it
 * doesn't, the request fails closed to text — never to a cloud transcription
 * service. His voice is never sent anywhere by this app.
 */
object VoiceInput {

    fun isAvailable(context: Context): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) { false }
    }

    private fun listenIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

    /**
     * One push-to-talk session. Call [start] on press, [stop] on release.
     * Results arrive via [onResult] as transcribed text (empty = nothing heard).
     */
    class Session(
        context: Context,
        private val onResult: (String) -> Unit,
        private val onError: (String) -> Unit
    ) {
        private val app = context.applicationContext
        private var recognizer: SpeechRecognizer? = null
        private var heard = false

        fun start() {
            if (!isAvailable(app)) {
                onError("Voice isn't available on this device — type it instead.")
                return
            }
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(app)
                recognizer = r
                heard = false
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = list?.firstOrNull()?.trim().orEmpty()
                        heard = text.isNotEmpty()
                        Log.i(TAG, "VoiceInput: heard '$text'")
                        onResult(text)
                    }

                    override fun onError(error: Int) {
                        Log.i(TAG, "VoiceInput: recognition error $error")
                        // Fail closed to text — never to a cloud service.
                        if (!heard) {
                            onError(
                                when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH,
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                        "Didn't catch that — try again, or just type it."
                                    SpeechRecognizer.ERROR_NETWORK,
                                    SpeechRecognizer.ERROR_SERVER ->
                                        "Voice needs the on-device pack here — typing works the same."
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                        "Voice needs the mic — tap to allow, or just type."
                                    else -> "Voice hiccup — type it instead, same result."
                                }
                            )
                        }
                    }
                })
                r.startListening(listenIntent())
                Log.i(TAG, "VoiceInput: listening")
            } catch (e: Exception) {
                Log.w(TAG, "VoiceInput: start failed (${e.message})")
                onError("Voice isn't available right now — type it instead.")
            }
        }

        fun stop() {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) { /* best-effort */ }
        }

        fun destroy() {
            try {
                recognizer?.destroy()
            } catch (e: Exception) { /* best-effort */ }
            recognizer = null
        }
    }
}
