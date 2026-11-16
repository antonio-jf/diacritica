package app.diacritica.ui

import app.diacritica.undo.deleteHistoryItem
import app.diacritica.undo.clearHistory
import app.diacritica.undo.removeSavedWord
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.diacritica.AppContainer
import app.diacritica.data.DetailResult
import app.diacritica.data.DictionaryInfo
import app.diacritica.data.FalseFriendWarning
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.data.ScriptPreference
import app.diacritica.data.WordInfo
import app.diacritica.data.online.OnlineLookup
import app.diacritica.settings.TranslateEngine
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
    val updater get() = c.updater
    val autoBackup get() = c.autoBackup

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
    fun setNavTabs(routes: List<String>) = edit { c.settings.setNavTabs(routes) }
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
    /** Machine-translated stand-ins for languages with nothing in the dictionary, by "conceptId:lang". */
    val suggestions: Map<String, WordSuggestion> = emptyMap(),
)

/** A machine translation offered when a language has no dictionary word for a meaning. */
data class WordSuggestion(val loading: Boolean = false, val text: String? = null, val by: String? = null, val error: String? = null)

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

            // A Mandarin word shown only from CC-CEDICT also goes online, for its English headwords' translations.
            val cedictOnly = r != null && r.concepts.isNotEmpty() && r.concepts.all { c.dictionary.isCedictOnly(it) }
            if (r != null && (r.concepts.isEmpty() || cedictOnly) && s.onlineLookup) {
                // Missing offline. While typing, wait for a pause before going online.
                if (!record) delay(900)
                _state.update { it.copy(online = OnlineStatus.FETCHING) }
                val (outcome, _) = c.online.fetchWord(q, s.sourceLang, s.activeLanguages)
                when (outcome) {
                    OnlineLookup.Outcome.FOUND -> {
                        r = lookupNow()
                        _state.update { it.copy(result = r, online = OnlineStatus.DOWNLOADED) }
                    }
                    // The word is already on screen from CC-CEDICT: no "not found" or queue message for it.
                    else -> if (cedictOnly) _state.update { it.copy(online = null) } else when (outcome) {
                        OnlineLookup.Outcome.OFFLINE -> {
                            c.settings.addPending("${s.sourceLang?.code ?: "auto"}:$q")
                            _state.update { it.copy(online = OnlineStatus.OFFLINE_QUEUED) }
                        }
                        OnlineLookup.Outcome.NOT_FOUND -> _state.update { it.copy(online = OnlineStatus.NOT_FOUND) }
                        else -> _state.update { it.copy(online = OnlineStatus.FAILED) }
                    }
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
                c.removeSavedWord(w.lang.code, w.word)
            } else {
                c.userDb.saved().save(SavedWord(lang = w.lang.code, word = w.word, note = w.gloss ?: w.pinyin))
                val s = c.settings.settings.first()
                c.review.ensureCards(w.lang, w.word, s.activeLanguages, s.script)
            }
        }
    }

    fun removeSaved(w: SavedWord) {
        viewModelScope.launch { c.removeSavedWord(w.lang, w.word) }
    }
    fun clearHistory() { viewModelScope.launch { c.clearHistory() } }
    fun deleteHistory(item: HistoryItem) { viewModelScope.launch { c.deleteHistoryItem(item) } }

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

    private fun suggestionKey(lang: Lang): String? {
        val r = _state.value.result ?: return null
        val concept = r.concepts.getOrNull(r.selected) ?: return null
        return "${concept.id}:${lang.code}"
    }

    fun suggestion(lang: Lang): WordSuggestion? = suggestionKey(lang)?.let { _state.value.suggestions[it] }

    /**
     * Machine translation of the selected meaning's English word into [lang]: Gemini (aware of the
     * meaning, gives articles) when it's the Translate engine and a key is set, else ML Kit on the phone.
     */
    fun suggest(lang: Lang) {
        val r = _state.value.result ?: return
        val concept = r.concepts.getOrNull(r.selected) ?: return
        val key = suggestionKey(lang) ?: return
        fun put(s: WordSuggestion) = _state.update { it.copy(suggestions = it.suggestions + (key to s)) }
        put(WordSuggestion(loading = true))
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val gemini = if (s.translateEngine == TranslateEngine.GEMINI && s.geminiKey.isNotBlank() && c.online.isOnline()) {
                runCatching { c.gemini.suggestWord(s.geminiKey, s.geminiModel, concept.enWord, concept.pos, concept.label, listOf(lang))[lang] }.getOrNull()
            } else null
            if (gemini != null) { put(WordSuggestion(text = gemini, by = "Gemini")); return@launch }
            // A Mandarin word known only from CC-CEDICT: translate the Chinese itself (more precise than "get up").
            val fromZh = c.dictionary.isCedictOnly(concept) && r.source == Lang.ZH
            runCatching {
                if (fromZh) c.translator.translate(r.headword?.word ?: r.query, Lang.ZH, lang)
                else c.translator.translate(concept.enWord, Lang.EN, lang)
            }
                .onSuccess { put(WordSuggestion(text = it, by = "on-device translation")) }
                .onFailure {
                    put(WordSuggestion(error = if (c.online.isOnline()) "Couldn't translate it. Try again." else
                        "The ${lang.englishName} translation model downloads once (about 30 MB); connect to the internet."))
                }
        }
    }

    /** Languages switched on in Settings: false-friend warnings only mention these. */
    val activeLanguages: StateFlow<List<Lang>> = c.settings.settings.map { it.activeLanguages }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** False friends among the headword and the first translation shown in each language. */
    fun falseFriends(r: LookupResult, languages: List<Lang>): List<FalseFriendWarning> {
        val words = buildList {
            r.headword?.let { add(it.lang to it.word) }
            if (r.headword == null) add(r.source to r.query)
            r.results.forEach { lr -> lr.words.firstOrNull()?.let { add(lr.lang to it.word) } }
        }
        return c.falseFriends.warningsFor(words, languages)
    }

    fun falseFriends(lang: Lang, word: String, languages: List<Lang>): List<FalseFriendWarning> =
        c.falseFriends.warnings(lang, word, languages)

    /**
     * Downloads the word again from Wiktionary (kaikki.org), which brings its etymology and synonyms
     * when the installed dictionary predates them. Returns a message when nothing could be fetched.
     */
    suspend fun fetchExtras(lang: Lang, word: String): String? {
        val (outcome, _) = c.online.fetchWord(word, lang, emptyList())
        return when (outcome) {
            OnlineLookup.Outcome.FOUND -> null
            OnlineLookup.Outcome.OFFLINE -> "You're offline."
            OnlineLookup.Outcome.NOT_FOUND -> "Wiktionary has no entry for this word."
            OnlineLookup.Outcome.FAILED -> "Couldn't reach Wiktionary. Try again later."
        }
    }
}

fun appViewModelFactory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(c) }
    initializer { DictionaryViewModel(c) }
    initializer { app.diacritica.ui.review.ReviewViewModel(c) }
    initializer { app.diacritica.ui.rehearse.RehearseViewModel(c) }
    initializer { app.diacritica.ui.reader.ReaderViewModel(c) }
    initializer { app.diacritica.ui.home.HomeViewModel(c) }
    initializer { app.diacritica.ui.translate.TranslateViewModel(c) }
    initializer { app.diacritica.ui.progress.ProgressViewModel(c) }
    initializer { app.diacritica.ui.tests.TestsViewModel(c) }
    initializer { app.diacritica.ui.grammar.GrammarViewModel(c) }
    initializer { app.diacritica.ui.packs.DataPacksViewModel(c, this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!) }
}
