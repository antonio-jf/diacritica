package app.diacritica.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import app.diacritica.data.Lang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * On-device text-to-speech. Offline voices must be installed in Android settings
 * (Settings › General management › Text-to-speech on Samsung; Google engine › Install voice data).
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    private val _missing = MutableStateFlow<Lang?>(null)
    /** Set when a language has no installed voice, so the UI can tell the user. */
    val missingVoice: StateFlow<Lang?> = _missing

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) pending?.invoke()
            pending = null
        }
    }

    fun speak(text: String, lang: Lang, rate: Float = 1f, slow: Boolean = false) {
        ensure()
        val action = {
            val engine = tts
            if (engine != null) {
                when (engine.setLanguage(lang.locale)) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> _missing.value = lang
                    else -> {
                        engine.setSpeechRate(if (slow) rate * 0.6f else rate)
                        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "diacritica-${text.hashCode()}")
                    }
                }
            }
        }
        if (ready) {
            action()
        } else {
            pending = action
        }
    }

    fun clearMissing() { _missing.value = null }

    /** Opens the system screen for downloading voice data. */
    fun openVoiceSettings() {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun shutdown() {
        tts?.shutdown(); tts = null; ready = false
    }
}
