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
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (preferOffline) deviceTags[lang] ?: tag(lang) else tag(lang))
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
     * The on-device recognizer's own tag for each language that has an offline pack, e.g. Mandarin is
     * "cmn-Hans-CN" there, not "zh-CN". Filled by [offlinePacks]; used to ask for offline recognition.
     */
    private val deviceTags = mutableMapOf<Lang, String>()

    /** Does an on-device language tag belong to [lang]? Mandarin is "cmn-…" (or "zh-…" on some phones). */
    private fun belongs(tag: String, lang: Lang): Boolean {
        val t = tag.lowercase()
        val first = t.substringBefore('-')
        return when (lang) {
            Lang.ZH -> first == "cmn" || (first == "zh" && !t.contains("hk") && !t.contains("yue"))
            else -> first == lang.code
        }
    }

    /** The best on-device tag for [lang] among [tags]: the one we'd use online first, then mainland Mandarin, then any. */
    private fun pick(tags: List<String>, lang: Lang): String? {
        val mine = tags.filter { belongs(it, lang) }
        return mine.firstOrNull { it.equals(tag(lang), ignoreCase = true) }
            ?: mine.firstOrNull { lang == Lang.ZH && it.lowercase().startsWith("cmn-hans") }
            ?: mine.firstOrNull()
    }

    /**
     * Which of [langs] have an offline speech pack on the phone (Android 13+, on-device recognizer).
     * Null when the phone can't tell.
     */
    suspend fun offlinePacks(langs: List<Lang>): Set<Lang>? {
        val support = support() ?: return null
        deviceTags.clear()
        val out = mutableSetOf<Lang>()
        for (l in langs) pick(support.installed, l)?.let { deviceTags[l] = it; out += l }
        return out
    }

    /** Languages the on-device recognizer has, can download, or is downloading. */
    private data class Support(val installed: List<String>, val available: List<String>)

    private suspend fun support(): Support? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) support33() else null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun support33(): Support? {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return null
        return try {
            suspendCancellableCoroutine { cont ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                r.checkRecognitionSupport(intent, context.mainExecutor, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(support: android.speech.RecognitionSupport) {
                        val s = Support(
                            installed = support.installedOnDeviceLanguages,
                            available = support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages + support.installedOnDeviceLanguages,
                        )
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        } finally {
            runCatching { r.destroy() }
        }
    }

    /**
     * Asks the phone to download the offline speech pack for [lang] (Android 13+), using the recognizer's own
     * tag for it. False when the phone's offline recognizer doesn't offer that language.
     */
    suspend fun downloadPack(lang: Lang): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val tag = support()?.let { pick(it.available, lang) } ?: return false
        return trigger33(tag)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun trigger33(tag: String): Boolean {
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return false
        return try {
            r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag))
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
