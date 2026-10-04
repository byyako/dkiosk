package com.byyako.dkiosk.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks announcements with Android's text-to-speech engine. The engine starts on first use and
 * texts sent while it's starting are spoken once it's ready.
 */
class Speaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var unavailable = false
    private var starts = 0
    private val waiting = mutableListOf<Pair<String, String?>>()

    val state: String
        get() = when {
            unavailable -> "unavailable"
            ready -> "ready"
            tts != null -> "starting"
            else -> "idle"
        }

    /** Returns an error message if there's no engine to speak with. */
    fun speak(text: String, language: String?): String? {
        if (unavailable) return NO_ENGINE
        if (ready) {
            say(text, language)
            return null
        }
        waiting += text to language
        if (tts == null) start()
        return if (unavailable) NO_ENGINE else null
    }

    /** Stops speaking. A missing engine is looked for again next time, in case one was installed. */
    fun stop() {
        waiting.clear()
        tts?.stop()
        unavailable = false
    }

    fun shutdown() {
        starts++
        waiting.clear()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun start() {
        val attempt = ++starts
        // With no engine installed, the failure is reported before the constructor returns.
        val engine = TextToSpeech(context) { status -> onInit(attempt, status) }
        if (attempt != starts || unavailable) {
            engine.shutdown()
            return
        }
        tts = engine
        if (ready) flush()
    }

    private fun onInit(attempt: Int, status: Int) {
        // A replaced or shut down engine can still report back; only the current one counts.
        if (attempt != starts) return
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            flush()
        } else {
            Log.w(TAG, "No text-to-speech engine (status $status)")
            unavailable = true
            waiting.clear()
            tts?.shutdown()
            tts = null
        }
    }

    private fun flush() {
        if (tts == null) return // Still in the constructor; start() flushes once it has the engine.
        waiting.forEach { (text, language) -> say(text, language) }
        waiting.clear()
    }

    private fun say(text: String, language: String?) {
        val engine = tts ?: return
        val locale = language?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "Text-to-speech has no voice for $locale, using its default")
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "dkiosk-${System.nanoTime()}")
    }

    private companion object {
        const val TAG = "Speaker"
        const val NO_ENGINE = "This device has no text-to-speech engine. Install one, such as Google's or RHVoice."
    }
}
