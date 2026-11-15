package app.diacritica.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.settings.TranslateEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One language's translation. */
data class TranslationResult(
    val text: String? = null,
    val pinyin: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val savedToReview: Boolean = false,
)

data class TranslateUiState(
    val input: String = "",
    /** Chosen source language; null = detect. */
    val source: Lang? = null,
    val detected: Lang? = null,
    /** The text the results belong to (the input may have been edited since). */
    val translatedText: String = "",
    val from: Lang? = null,
    val results: Map<Lang, TranslationResult> = emptyMap(),
    val busy: Boolean = false,
    val downloading: Set<Lang> = emptySet(),
    val engineUsed: TranslateEngine? = null,
    val notice: String? = null,
    val error: String? = null,
)

class TranslateViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun setInput(t: String) = _state.update { it.copy(input = t.take(MAX_CHARS)) }
    fun setSource(l: Lang?) = _state.update { it.copy(source = l) }
    fun clear() { job?.cancel(); _state.value = TranslateUiState(source = _state.value.source) }

    fun setEngine(e: TranslateEngine) { viewModelScope.launch { c.settings.setTranslateEngine(e) } }

    /** Opens Translate with text from elsewhere (text selected in another app) and translates it. */
    fun start(text: String) { _state.update { it.copy(input = text.take(MAX_CHARS), source = null) }; translate() }

    /** Continue from a result: it becomes the new text to translate. */
    fun fromResult(lang: Lang) {
        val t = _state.value.results[lang]?.text ?: return
        _state.update { it.copy(input = t, source = lang) }
        translate()
    }

    fun translate() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            val s = c.settings.settings.first()
            _state.update { it.copy(busy = true, error = null, notice = null, results = emptyMap(), detected = null, downloading = emptySet()) }
            val chosen = _state.value.source
            val from = chosen ?: c.translator.detect(text)
            if (from == null) {
                _state.update { it.copy(busy = false, error = "Couldn't tell which language this is. Pick it under From.") }
                return@launch
            }
            val targets = s.activeLanguages.filter { it != from }
            _state.update {
                it.copy(detected = if (chosen == null) from else null, from = from, translatedText = text,
                    results = targets.associateWith { TranslationResult() })
            }

            var engine = s.translateEngine
            if (engine == TranslateEngine.GEMINI && (s.geminiKey.isBlank() || !c.online.isOnline())) {
                engine = TranslateEngine.ON_DEVICE
                _state.update {
                    it.copy(notice = if (s.geminiKey.isBlank()) "No Gemini key, so this was translated on the phone."
                    else "You're offline, so this was translated on the phone.")
                }
            }
            _state.update { it.copy(engineUsed = engine) }

            if (engine == TranslateEngine.GEMINI) {
                try {
                    val out = c.gemini.translate(s.geminiKey, s.geminiModel, from, text, targets)
                    for (l in targets) setResult(l, out[l], if (out[l] == null) "No translation returned." else null)
                } catch (e: Exception) {
                    _state.update { it.copy(notice = "Gemini failed (${e.message ?: "error"}); translating on the phone instead.", engineUsed = TranslateEngine.ON_DEVICE) }
                    onDevice(text, from, targets.filter { _state.value.results[it]?.text == null })
                }
            } else {
                onDevice(text, from, targets)
            }
            _state.update { it.copy(busy = false, downloading = emptySet()) }
        }
    }

    private suspend fun onDevice(text: String, from: Lang, targets: List<Lang>) {
        for (l in targets) {
            try {
                val out = c.translator.translate(text, from, l) { lang -> _state.update { it.copy(downloading = it.downloading + lang) } }
                setResult(l, out, null)
            } catch (e: Exception) {
                val msg = if (!c.online.isOnline()) "The ${l.englishName} model isn't on the phone yet; connect once to download it (about 30 MB)."
                else e.message ?: "Translation failed."
                setResult(l, null, msg)
            }
        }
    }

    private suspend fun setResult(l: Lang, text: String?, error: String?) {
        val pinyin = if (l == Lang.ZH && text != null) runCatching { c.dictionary.pinyinOf(text) }.getOrNull()?.ifBlank { null } else null
        _state.update { st ->
            st.copy(results = st.results + (l to TranslationResult(text = text?.trim(), pinyin = pinyin, loading = false, error = error)))
        }
    }

    fun speak(lang: Lang, text: String) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate) }
    }

    /** A Review card: your text on the front, the translation on the back. */
    fun saveToReview(lang: Lang) {
        val st = _state.value
        val t = st.results[lang]?.text ?: return
        viewModelScope.launch {
            c.review.addSentenceCard(lang, st.translatedText, t)
            _state.update { s -> s.copy(results = s.results + (lang to (s.results[lang] ?: return@update s).copy(savedToReview = true))) }
        }
    }

    companion object { const val MAX_CHARS = 5000 }
}
