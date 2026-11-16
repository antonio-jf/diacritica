package app.diacritica.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.diacritica.data.Lang
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

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

    /** Utterances someone is waiting on (read-aloud), by id. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var counter = 0L

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.complete(true) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.complete(false) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { waiting.remove(it)?.complete(false) } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { waiting.remove(it)?.complete(false) } }
                })
            }
            pending?.invoke()
            pending = null
        }
    }

    private suspend fun awaitReady(): Boolean {
        ensure()
        if (ready) return true
        return suspendCancellableCoroutine { cont ->
            val prev = pending
            pending = { prev?.invoke(); if (cont.isActive) cont.resume(ready) }
        }
    }

    /**
     * Speaks [text] and returns when it has finished (true) or was stopped or failed (false).
     * Used by read-aloud to go sentence by sentence.
     */
    suspend fun speakAndWait(text: String, lang: Lang, rate: Float = 1f): Boolean {
        if (text.isBlank()) return true
        if (!awaitReady()) return false
        val engine = tts ?: return false
        when (engine.setLanguage(lang.locale)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> { _missing.value = lang; return false }
        }
        engine.setSpeechRate(rate)
        val id = "read-${counter++}"
        val done = CompletableDeferred<Boolean>()
        waiting[id] = done
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) { waiting.remove(id); return false }
        return try { done.await() } finally { waiting.remove(id) }
    }

    /** Stops whatever is being said. */
    fun stop() {
        tts?.stop()
        waiting.values.forEach { it.complete(false) }
        waiting.clear()
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
