package com.pocketds.hub.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Something that says a word or a phrase aloud (#62): the device's voice, or a test's stand-in. */
interface SpeechVoice {
    fun say(text: String)
    fun stop()
    fun release()
}

/**
 * The speaker in the dictionary card (#62): Android's own text-to-speech, with an offline English voice, so it works on a train.
 * The engine starts the first time something is to be said, and the first words wait for it; a device without a voice says so
 * rather than staying silent.
 */
class ReaderVoice(context: Context, private val onProblem: (String) -> Unit) : SpeechVoice {
    private val app = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var waiting: String? = null

    override fun say(text: String) {
        if (text.isBlank() || failed) return
        waiting = text
        if (engine == null) {
            engine = TextToSpeech(app) { status ->
                if (status != TextToSpeech.SUCCESS || !configure()) {
                    failed = true
                    onProblem("This device has no offline English voice")
                    return@TextToSpeech
                }
                ready = true
                waiting?.let(::speakNow)
            }
        } else if (ready) speakNow(text)
    }

    private fun configure(): Boolean {
        val tts = engine ?: return false
        if (tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) tts.language = Locale.US
        else if (tts.isLanguageAvailable(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE) tts.language = Locale.ENGLISH
        else return false
        // An English voice that needs no network, when the engine lists voices at all.
        tts.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }?.let { offline ->
            offline.firstOrNull { it.locale == Locale.US }?.let { tts.voice = it } ?: offline.firstOrNull()?.let { tts.voice = it }
        }
        tts.setSpeechRate(0.95f)
        return true
    }

    private fun speakNow(text: String) {
        waiting = null
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pocket-word")
    }

    override fun stop() { waiting = null; engine?.stop() }

    override fun release() {
        waiting = null
        engine?.shutdown()
        engine = null
        ready = false
    }
}
