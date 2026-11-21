package app.diacritica.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.diacritica.data.Lang
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What the recognizer heard: its guesses, best first, or why it heard nothing. */
data class Heard(val texts: List<String>, val error: String? = null) {
    val best: String? get() = texts.firstOrNull()
}

/**
 * Listens for one spoken answer in a given language. Each call says which language to expect, so the
 * drill can switch from French to Mandarin to German between questions. Google's recognizer is used when
 * it's installed (Samsung's default handles some of these languages less well), and the offline pack is
 * used when the phone has it.
 */
class VoiceListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /** Google's recognition service, if installed. */
    private val googleService: ComponentName? by lazy {
        runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
                .firstOrNull { it.packageName.startsWith("com.google") }
        }.getOrNull()
    }

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread (SpeechRecognizer's rule). */
    suspend fun listen(lang: Lang, preferOffline: Boolean, onPartial: (String) -> Unit, onLevel: (Float) -> Unit = {}): Heard {
        stop()
        val r = googleService?.let { runCatching { SpeechRecognizer.createSpeechRecognizer(context, it) }.getOrNull() }
            ?: SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag(lang))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // A single word is short: don't cut off too early, don't wait too long after.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        return try {
            suspendCancellableCoroutine { cont ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { onLevel(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                    }
                    override fun onResults(results: Bundle?) {
                        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                        if (cont.isActive) cont.resume(if (texts.isEmpty()) Heard(emptyList(), "Didn't catch that.") else Heard(texts))
                    }
                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(Heard(emptyList(), message(error)))
                    }
                })
                cont.invokeOnCancellation { runCatching { r.cancel() } }
                r.startListening(intent)
            }
        } finally {
            runCatching { r.destroy() }
            if (recognizer === r) recognizer = null
        }
    }

    fun stop() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    /**
     * Which of [langs] have an offline speech pack on the phone (Android 13+, on-device recognizer).
     * Null when the phone can't tell.
     */
    suspend fun offlinePacks(langs: List<Lang>): Set<Lang>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) offlinePacks33(langs) else null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun offlinePacks33(langs: List<Lang>): Set<Lang>? {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return null
        return try {
            suspendCancellableCoroutine { cont ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                r.checkRecognitionSupport(intent, context.mainExecutor, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(support: android.speech.RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.map { it.lowercase() }
                        val set = langs.filter { l -> installed.any { it == tag(l).lowercase() || it.substringBefore('-') == l.code } }.toSet()
                        if (cont.isActive) cont.resume(set)
                    }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        } finally {
            runCatching { r.destroy() }
        }
    }

    /** Asks the phone to download the offline speech pack for [lang] (Android 13+). */
    fun downloadPack(lang: Lang): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && downloadPack33(lang)

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun downloadPack33(lang: Lang): Boolean {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return false
        return try {
            r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag(lang)))
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { r.destroy() }
        }
    }

    companion object {
        fun tag(lang: Lang): String = when (lang) {
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en-US"
            else -> lang.locale.toLanguageTag()
        }

        private fun message(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Diacritica needs microphone access."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "No offline speech pack for this language, and no connection."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again."
            12, 13 -> "This language isn't available for speech recognition on this phone."
            else -> "Speech recognition stopped (error $error)."
        }
    }
}
