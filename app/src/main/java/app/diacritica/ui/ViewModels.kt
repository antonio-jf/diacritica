package app.diacritica.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.diacritica.AppContainer
import app.diacritica.data.DetailResult
import app.diacritica.data.DictionaryInfo
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.data.ScriptPreference
import app.diacritica.data.WordInfo
import app.diacritica.data.online.OnlineLookup
import app.diacritica.data.user.HistoryItem
import app.diacritica.data.user.SavedWord
import app.diacritica.settings.AccentChoice
import app.diacritica.settings.AppSettings
import app.diacritica.settings.DictionaryLayout
import app.diacritica.settings.ThemeId
import app.diacritica.settings.ThemeMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings, shared by the whole app. `null` until DataStore has loaded (avoids a theme flash). */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _dictInfo = MutableStateFlow<DictionaryInfo?>(null)
    val dictInfo: StateFlow<DictionaryInfo?> = _dictInfo.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { c.dictionaryDb.version.collect { _dictInfo.value = runCatching { c.dictionaryDb.info() }.getOrNull() } }
    }

    private fun edit(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    fun finishOnboarding(t: ThemeId) = edit { c.settings.finishOnboarding(t) }
    fun setTheme(t: ThemeId) = edit { c.settings.setTheme(t) }
    fun setMode(m: ThemeMode) = edit { c.settings.setMode(m) }
    fun setAccent(a: AccentChoice) = edit { c.settings.setAccent(a) }
    fun setTextScale(v: Float) = edit { c.settings.setTextScale(v) }
    fun setLanguageColors(v: Boolean) = edit { c.settings.setLanguageColors(v) }
    fun setDictionaryLayout(l: DictionaryLayout) = edit { c.settings.setDictionaryLayout(l) }
    fun resetLayouts() = edit { c.settings.resetLayouts() }
    fun setScript(s: ScriptPreference) = edit { c.settings.setScript(s) }
    fun setSpeechRate(v: Float) = edit { c.settings.setSpeechRate(v) }
    fun setSourceLang(l: Lang?) = edit { c.settings.setSourceLang(l) }
    fun setOnlineLookup(v: Boolean) = edit { c.settings.setOnlineLookup(v) }
    fun setNewCardsPerDay(v: Int) = edit { c.settings.setNewCardsPerDay(v) }
    fun setGeminiKey(v: String) = edit { c.settings.setGeminiKey(v) }
    fun setGeminiModel(v: String) = edit { c.settings.setGeminiModel(v) }
    fun setStrictAccents(v: Boolean) = edit { c.settings.setStrictAccents(v) }
    fun setStrictTones(v: Boolean) = edit { c.settings.setStrictTones(v) }
    fun setRehearseLayout(v: app.diacritica.settings.RehearseLayout) = edit { c.settings.setRehearseLayout(v) }
    fun setReaderLayout(v: app.diacritica.settings.ReaderLayout) = edit { c.settings.setReaderLayout(v) }
    fun setReaderPopup(v: app.diacritica.settings.PopupLayout) = edit { c.settings.setReaderPopup(v) }
    fun setPinyinHint(v: Boolean) = edit { c.settings.setPinyinHint(v) }
    fun setReviewFromHistory(v: Boolean) = edit { c.settings.setReviewFromHistory(v) }
    fun toggleRehearseLang(l: Lang) = edit {
        val cur = c.settings.settings.first().rehearseLanguages.toSet()
        val next = if (l in cur) cur - l else cur + l
        if (next.isNotEmpty()) c.settings.setRehearseLangs(next)
    }
    fun toggleLevel(level: String) = edit {
        val cur = c.settings.settings.first().levels
        val next = if (level in cur) cur - level else cur + level
        if (next.isNotEmpty()) c.settings.setLevels(next)
    }

    /** Sends a tiny request to check the Gemini key; reports the result as a message. */
    fun testGeminiKey() = edit {
        val s = c.settings.settings.first()
        _message.value = "Checking the key…"
        _message.value = try {
            c.gemini.translate(s.geminiKey, s.geminiModel, Lang.EN, "Good morning.", listOf(Lang.ES))
            "The Gemini key works."
        } catch (e: Exception) {
            e.message ?: "The key could not be checked."
        }
    }
    fun fetchedCount(): Int = c.dictionaryDb.fetchedCount()

    fun toggleLanguage(l: Lang, on: Boolean) = edit {
        val s = c.settings.settings.first()
        val next = if (on) s.enabled + l else s.enabled - l
        if (next.size >= 2) {
            c.settings.setEnabled(next)
        } else {
            _message.value = "Keep at least two languages active."
        }
    }

    fun moveLanguage(l: Lang, delta: Int) = edit {
        val order = c.settings.settings.first().languageOrder.toMutableList()
        val i = order.indexOf(l)
        val j = (i + delta).coerceIn(0, order.lastIndex)
        if (i >= 0 && i != j) { order.removeAt(i); order.add(j, l); c.settings.setLanguageOrder(order) }
    }

    fun importDictionary(uri: Uri) = edit {
        _message.value = "Importing…"
        _message.value = c.dictionaryDb.import(uri) ?: "Dictionary imported."
    }

    fun exportBackup(uri: Uri) = edit {
        _message.value = "Saving backup…"
        _message.value = runCatching { c.backup.export(uri) }.getOrElse { "Backup failed: ${it.message}" }
    }

    /** On success the app restarts with the restored data, so only errors come back. */
    fun restoreBackup(uri: Uri) = edit {
        _message.value = "Restoring…"
        _message.value = runCatching { c.backup.restore(uri) }.getOrElse { "Restore failed: ${it.message}" }
    }

    fun resetDictionary() = edit { c.dictionaryDb.resetToBundled(); _message.value = "Using the bundled dictionary." }

    fun openVoiceSettings() = c.speaker.openVoiceSettings()
    fun clearMessage() { _message.value = null }
}

/** What happened when a word was missing offline. */
enum class OnlineStatus { FETCHING, DOWNLOADED, NOT_FOUND, OFFLINE_QUEUED, FAILED }

data class DictionaryUiState(
    val query: String = "",
    val loading: Boolean = false,
    val result: LookupResult? = null,
    val selectedSense: Int = 0,
    val online: OnlineStatus? = null,
)

@OptIn(FlowPreview::class)
class DictionaryViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DictionaryUiState())
    val state: StateFlow<DictionaryUiState> = _state.asStateFlow()

    val history: StateFlow<List<HistoryItem>> = c.userDb.history().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saved: StateFlow<List<SavedWord>> = c.userDb.saved().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savedKeys: StateFlow<Set<String>> = c.userDb.saved().keys().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val missingVoice = c.speaker.missingVoice

    private var searchJob: Job? = null

    init {
        // Re-run the current search when settings that affect results change, or a new dictionary is imported.
        viewModelScope.launch {
            c.settings.settings.map { listOf(it.activeLanguages, it.sourceLang, it.script) }.distinctUntilChanged().collect { search(debounce = false, record = false) }
        }
        viewModelScope.launch { c.dictionaryDb.version.collect { if (it > 0) search(debounce = false, record = false) } }
        viewModelScope.launch { processPending() }
    }

    /** Downloads words that were searched while offline. */
    private suspend fun processPending() {
        val s = c.settings.settings.first()
        if (!s.onlineLookup || !c.online.isOnline()) return
        for (key in c.settings.pendingWords.first()) {
            val lang = Lang.fromCode(key.substringBefore(':'))
            val word = key.substringAfter(':')
            val (outcome, _) = c.online.fetchWord(word, lang, s.activeLanguages)
            if (outcome != OnlineLookup.Outcome.OFFLINE && outcome != OnlineLookup.Outcome.FAILED) c.settings.removePending(key)
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q, selectedSense = 0) }
        search(debounce = true, record = false)
    }

    fun submit() = search(debounce = false, record = true)

    /** Search for [word]; [lang] sets the source language (pivot), null keeps the current one. */
    fun lookUp(word: String, lang: Lang?) {
        viewModelScope.launch {
            if (lang != null) c.settings.setSourceLang(lang)
            _state.update { it.copy(query = word, selectedSense = 0) }
            search(debounce = false, record = true)
        }
    }

    fun selectSense(i: Int) {
        _state.update { it.copy(selectedSense = i) }
        search(debounce = false, record = false)
    }

    fun clear() {
        searchJob?.cancel()
        _state.value = DictionaryUiState()
    }

    private fun search(debounce: Boolean, record: Boolean) {
        searchJob?.cancel()
        val q = _state.value.query.trim()
        if (q.isEmpty()) { _state.update { it.copy(result = null, loading = false, online = null) }; return }
        searchJob = viewModelScope.launch {
            if (debounce) delay(250)
            _state.update { it.copy(loading = true, online = null) }
            val s = c.settings.settings.first()
            suspend fun lookupNow() = runCatching {
                c.dictionary.lookup(q, s.sourceLang, s.activeLanguages, s.script, _state.value.selectedSense)
            }.getOrNull()
            var r = lookupNow()
            _state.update { it.copy(result = r, loading = false) }

            if (r != null && r.concepts.isEmpty() && s.onlineLookup) {
                // Missing offline. While typing, wait for a pause before going online.
                if (!record) delay(900)
                _state.update { it.copy(online = OnlineStatus.FETCHING) }
                val (outcome, _) = c.online.fetchWord(q, s.sourceLang, s.activeLanguages)
                when (outcome) {
                    OnlineLookup.Outcome.FOUND -> {
                        r = lookupNow()
                        _state.update { it.copy(result = r, online = OnlineStatus.DOWNLOADED) }
                    }
                    OnlineLookup.Outcome.OFFLINE -> {
                        c.settings.addPending("${s.sourceLang?.code ?: "auto"}:$q")
                        _state.update { it.copy(online = OnlineStatus.OFFLINE_QUEUED) }
                    }
                    OnlineLookup.Outcome.NOT_FOUND -> _state.update { it.copy(online = OnlineStatus.NOT_FOUND) }
                    OnlineLookup.Outcome.FAILED -> _state.update { it.copy(online = OnlineStatus.FAILED) }
                }
            }

            // Fill in gender/plural/examples for translations that have no entry of their own yet.
            val current = r
            if (current != null && current.concepts.isNotEmpty() && s.onlineLookup && c.online.enrich(current.results)) {
                r = lookupNow()
                _state.update { it.copy(result = r) }
            }

            val final = r
            if (record && final != null && !final.isEmpty) {
                c.userDb.history().deleteSame(q, final.source.code)
                c.userDb.history().insert(HistoryItem(query = q, lang = final.source.code))
            }
        }
    }

    /** Saving a word also creates its review cards; removing it deletes them. */
    fun toggleSave(w: WordInfo) {
        viewModelScope.launch {
            val key = "${w.lang.code}:${w.word}"
            if (key in savedKeys.value) {
                c.userDb.saved().remove(w.lang.code, w.word)
                c.review.removeWordCards(w.lang, w.word)
            } else {
                c.userDb.saved().save(SavedWord(lang = w.lang.code, word = w.word, note = w.gloss ?: w.pinyin))
                val s = c.settings.settings.first()
                c.review.ensureCards(w.lang, w.word, s.activeLanguages, s.script)
            }
        }
    }

    fun removeSaved(w: SavedWord) {
        viewModelScope.launch {
            c.userDb.saved().remove(w.lang, w.word)
            Lang.fromCode(w.lang)?.let { c.review.removeWordCards(it, w.word) }
        }
    }
    fun clearHistory() { viewModelScope.launch { c.userDb.history().clear() } }

    fun speak(w: WordInfo, slow: Boolean = false) {
        viewModelScope.launch {
            val rate = c.settings.settings.first().speechRate
            val text = if (w.article != null && !w.article.endsWith("'")) "${w.article} ${w.word}" else (w.article ?: "") + w.word
            c.speaker.speak(text, w.lang, rate, slow)
        }
    }

    fun speakText(text: String, lang: Lang) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate) }
    }

    fun clearMissingVoice() = c.speaker.clearMissing()
    fun openVoiceSettings() = c.speaker.openVoiceSettings()

    suspend fun detail(lang: Lang, word: String): DetailResult = c.dictionary.detail(lang, word)
}

fun appViewModelFactory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(c) }
    initializer { DictionaryViewModel(c) }
    initializer { app.diacritica.ui.review.ReviewViewModel(c) }
    initializer { app.diacritica.ui.rehearse.RehearseViewModel(c) }
    initializer { app.diacritica.ui.reader.ReaderViewModel(c) }
    initializer { app.diacritica.ui.home.HomeViewModel(c) }
    initializer { app.diacritica.ui.translate.TranslateViewModel(c) }
}
