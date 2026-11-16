package app.diacritica.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.DictionaryRepository
import app.diacritica.data.FalseFriendWarning
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.data.WordInfo
import app.diacritica.data.user.SavedWord
import app.diacritica.data.user.Story
import app.diacritica.reader.LoadedStory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The word popup: what was long-pressed and what the dictionary says about it. */
/** A pressed word's position in window pixels: horizontal centre, line top and line bottom. */
data class WordAnchor(val x: Float, val top: Float, val bottom: Float)

data class WordPopup(
    val lang: Lang,
    val token: String,
    val sentenceIdx: Int,
    val loading: Boolean = true,
    val result: LookupResult? = null,
    val fetching: Boolean = false,
    /** Where the word is on screen (window coordinates), for the Tooltip style. */
    val anchor: WordAnchor? = null,
    /** Tooltip opened into the full sheet ("More"). */
    val expanded: Boolean = false,
    /** The matching word in the other language's sentence, if the dictionary's translations appear in it. */
    val match: String? = null,
    val matchLang: Lang? = null,
)

data class ShelfItem(val story: Story, val titles: Map<Lang, String>)

data class ReaderUiState(
    /** Sentences whose hidden translation you've revealed ("Hide the translation until tapped"). */
    val revealed: Set<Int> = emptySet(),
    val story: LoadedStory? = null,
    val highlighted: Int? = null,
    val popup: WordPopup? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** The Classics tab: public-domain books in two languages (data pack or bundled). */
data class ClassicsState(
    val available: Boolean = false,
    val loading: Boolean = true,
    val books: List<app.diacritica.reader.BookInfo> = emptyList(),
    val query: String = "",
    /** Only books in your Reader pair (either order). */
    val onlyPair: Boolean = true,
    /** The book being put on your shelf. */
    val opening: Long? = null,
)

class ReaderViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val shelf: StateFlow<List<ShelfItem>> = c.stories.all()
        .map { list -> list.map { ShelfItem(it, c.stories.titles(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedKeys: StateFlow<Set<String>> = c.userDb.saved().keys().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val zhCache = java.util.concurrent.ConcurrentHashMap<String, List<DictionaryRepository.ZhToken>>()

    init { viewModelScope.launch { runCatching { c.stories.ensureStarter() } } }

    private val _classics = MutableStateFlow(ClassicsState())
    val classics: StateFlow<ClassicsState> = _classics.asStateFlow()

    fun loadClassics() {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val cs = _classics.value
            val available = c.books.available()
            val list = if (available) runCatching {
                c.books.list(if (cs.onlyPair) s.readerLangA to s.readerLangB else null, cs.query)
            }.getOrDefault(emptyList()) else emptyList()
            _classics.update { it.copy(available = available, loading = false, books = list) }
        }
    }

    fun setClassicsQuery(q: String) { _classics.update { it.copy(query = q) }; loadClassics() }
    fun setOnlyPair(on: Boolean) { _classics.update { it.copy(onlyPair = on) }; loadClassics() }

    /** Puts the book on your shelf (the first time) and opens it in its two languages. */
    fun openBook(b: app.diacritica.reader.BookInfo) {
        if (_classics.value.opening != null) return
        _classics.update { it.copy(opening = b.id) }
        viewModelScope.launch {
            try {
                val sentences = c.books.sentences(b.id)
                if (sentences.isEmpty()) { _state.update { it.copy(error = "This book couldn't be read.") }; return@launch }
                val id = c.stories.importBook(b, sentences)
                val s = c.settings.settings.first()
                if (!(b.has(s.readerLangA) && b.has(s.readerLangB) && s.readerLangA != s.readerLangB)) c.settings.setReaderLangs(b.a, b.b)
                open(id)
            } finally {
                _classics.update { it.copy(opening = null) }
            }
        }
    }

    fun open(id: Long) {
        viewModelScope.launch {
            val s = c.stories.load(id) ?: return@launch
            _state.update { it.copy(story = s, highlighted = s.story.lastSentence.takeIf { i -> i > 0 }, popup = null) }
            c.stories.saveProgress(id, s.story.lastSentence)
        }
    }

    fun close() {
        if (reading.value?.storyId == _state.value.story?.story?.id) c.readAloud.stop()
        _state.update { it.copy(story = null, highlighted = null, popup = null, revealed = emptySet()) }
    }

    /** What read-aloud is playing (any story), for the play/stop button. */
    val reading: StateFlow<app.diacritica.reader.ReadAloudState?> = c.readAloud.state

    /**
     * Reads the open story aloud from the highlighted (or last read) sentence to the end, in [langs]
     * order for each sentence, highlighting and scrolling along.
     */
    fun readAloud(langs: List<Lang>) {
        val story = _state.value.story ?: return
        val from = _state.value.highlighted ?: story.story.lastSentence.coerceAtLeast(0)
        viewModelScope.launch {
            val rate = c.settings.settings.first().speechRate
            c.readAloud.play(story.story.id, story.title(langs.first()), story.sentences, from, langs, rate) { i ->
                _state.update { st -> if (st.story?.story?.id == story.story.id) st.copy(highlighted = i) else st }
                viewModelScope.launch { c.stories.saveProgress(story.story.id, i) }
            }
        }
        lastReadLangs = langs
    }

    private var lastReadLangs: List<Lang> = emptyList()

    fun stopReading() = c.readAloud.stop()

    fun reveal(idx: Int) = _state.update { it.copy(revealed = it.revealed + idx) }

    fun setTapLookup(on: Boolean) { viewModelScope.launch { c.settings.setReaderTapLookup(on) } }
    fun setHideOther(on: Boolean) { viewModelScope.launch { c.settings.setReaderHideOther(on) } }

    fun delete(id: Long) { viewModelScope.launch { c.stories.delete(id) } }

    /** False friends for a tapped word, against the other reading language and English. */
    fun falseFriends(p: WordPopup, other: Lang): List<FalseFriendWarning> {
        val langs = listOf(other, Lang.EN).distinct()
        val words = buildList {
            add(p.lang to p.token)
            p.result?.headword?.let { add(it.lang to it.word) }
        }
        return c.falseFriends.warningsFor(words, langs, max = 2)
    }

    fun setLangs(a: Lang, b: Lang) { viewModelScope.launch { c.settings.setReaderLangs(a, b) } }

    fun setLayout(l: app.diacritica.settings.ReaderLayout) { viewModelScope.launch { c.settings.setReaderLayout(l) } }

    fun setSplit(f: Float) { viewModelScope.launch { c.settings.setReaderSplit(f) } }

    fun setPinyin(on: Boolean) { viewModelScope.launch { c.settings.setReaderPinyin(on) } }

    fun setPinyinMode(mode: app.diacritica.settings.PinyinMode, top: Int? = null) {
        viewModelScope.launch { c.settings.setReaderPinyinMode(mode, top) }
    }

    /** True if [word] has a character outside the [top] most common, so its pinyin is shown in "uncommon" mode. */
    fun isRare(word: String, top: Int): Boolean = c.zhLexicon.hasRareChar(word, top)

    fun setStoryLevel(level: String, hsk: Int) { viewModelScope.launch { c.settings.setStoryLevel(level, hsk) } }

    /** Tap on a sentence: highlight it in both languages and remember the position. */
    fun highlight(idx: Int) {
        val s = _state.value.story ?: return
        // While reading aloud, tapping a sentence jumps there.
        if (reading.value?.storyId == s.story.id && lastReadLangs.isNotEmpty()) {
            _state.update { it.copy(highlighted = idx) }
            readAloud(lastReadLangs)
            return
        }
        val next = if (_state.value.highlighted == idx) null else idx
        _state.update { it.copy(highlighted = next) }
        if (next != null) viewModelScope.launch { c.stories.saveProgress(s.story.id, next) }
    }

    suspend fun zhTokens(text: String): List<DictionaryRepository.ZhToken> =
        zhCache[text] ?: runCatching { c.dictionary.segmentZh(text) }.getOrNull()
            // Only successful splits are kept: a failure (dictionary still opening) is retried next time,
            // and meanwhile every character can still be pressed on its own.
            ?.also { if (it.isNotEmpty()) zhCache[text] = it }
            ?: text.map { DictionaryRepository.ZhToken(it.toString(), null, null) }

    /** Long-press on a word: look it up (offline first; downloads it if missing and allowed). */
    fun lookUpWord(lang: Lang, rawToken: String, sentenceIdx: Int, otherLang: Lang, anchor: WordAnchor? = null) {
        val token = rawToken.trim().trim('\'', '’', '-', '«', '»', '“', '”', '„', '"', '—')
        if (token.isEmpty()) return
        _state.update { it.copy(popup = WordPopup(lang, token, sentenceIdx, anchor = anchor), highlighted = sentenceIdx) }
        viewModelScope.launch {
            val s = c.settings.settings.first()
            // The word's language, the other Reader language, English, then the rest of your languages.
            val langs = (listOf(lang, otherLang, Lang.EN) + s.activeLanguages).distinct()
            suspend fun run() = runCatching { c.dictionary.lookup(token, lang, langs, s.script) }.getOrNull()
            var r = run()
            _state.update { st -> if (st.popup?.token == token) st.copy(popup = st.popup.copy(loading = false, result = r)) else st }
            if ((r == null || r.concepts.isEmpty()) && s.onlineLookup && lang != Lang.ZH) {
                _state.update { st -> if (st.popup?.token == token) st.copy(popup = st.popup.copy(fetching = true)) else st }
                c.online.fetchWord(token, lang, langs)
                r = run()
                _state.update { st -> if (st.popup?.token == token) st.copy(popup = st.popup.copy(fetching = false, result = r)) else st }
            }
            // Underline the translation in the other language's sentence.
            val other = _state.value.story?.sentences?.getOrNull(sentenceIdx)?.get(otherLang)
            val candidates = r?.results?.firstOrNull { it.lang == otherLang }?.words
                ?.flatMap { w -> listOfNotNull(w.word, w.traditional) }.orEmpty()
            val match = other?.let { app.diacritica.reader.WordMatch.find(it, otherLang, candidates) }
            if (match != null) _state.update { st -> if (st.popup?.token == token) st.copy(popup = st.popup.copy(match = match, matchLang = otherLang)) else st }
            // Record it like a Dictionary search, so it feeds Review and "My words".
            if (r != null && r.concepts.isNotEmpty()) {
                c.userDb.history().deleteSame(token, lang.code)
                c.userDb.history().insert(app.diacritica.data.user.HistoryItem(query = token, lang = lang.code))
            }
        }
    }

    fun dismissPopup() = _state.update { it.copy(popup = null) }

    /** Tooltip → full sheet. */
    fun expandPopup() = _state.update { st -> st.copy(popup = st.popup?.copy(expanded = true)) }

    fun setPopupLayout(l: app.diacritica.settings.PopupLayout) { viewModelScope.launch { c.settings.setReaderPopup(l) } }

    fun speak(lang: Lang, text: String, slow: Boolean = false) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate, slow) }
    }

    fun speakWord(w: WordInfo) {
        val text = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
        speak(w.lang, text)
    }

    fun toggleSave(w: WordInfo) {
        viewModelScope.launch {
            val key = "${w.lang.code}:${w.word}"
            if (key in savedKeys.value) {
                c.userDb.saved().remove(w.lang.code, w.word); c.review.removeWordCards(w.lang, w.word)
            } else {
                val s = c.settings.settings.first()
                val story = _state.value.story
                val ctx = _state.value.popup?.let { p -> story?.sentences?.getOrNull(p.sentenceIdx)?.get(p.lang) }
                c.userDb.saved().save(SavedWord(lang = w.lang.code, word = w.word, note = ctx ?: w.gloss))
                c.review.ensureCards(w.lang, w.word, s.activeLanguages, s.script)
            }
        }
    }

    /** The highlighted sentence as a Review card: language A → language B. */
    fun addSentenceToReview(idx: Int, a: Lang, b: Lang) {
        val s = _state.value.story ?: return
        val front = s.sentences.getOrNull(idx)?.get(a) ?: return
        val back = s.sentences.getOrNull(idx)?.get(b) ?: return
        viewModelScope.launch {
            c.review.addSentenceCard(b, front, back)
            _state.update { it.copy(message = "Added to Review.") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    /** Writes a new story with Gemini and opens it. */
    fun generate(topic: String, level: String? = null) {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            if (s.geminiKey.isBlank()) { _state.update { it.copy(error = "Add your Gemini API key in Settings › Rehearse to write new stories.") }; return@launch }
            if (!c.online.isOnline()) { _state.update { it.copy(error = "You're offline. New stories need the internet; saved ones work offline.") }; return@launch }
            _state.update { it.copy(generating = true, error = null) }
            try {
                val id = c.stories.generate(s.geminiKey, s.geminiModel, topic.ifBlank { "a surprising day in a small town" }, level ?: s.storyLevel, s.storyHsk)
                _state.update { it.copy(generating = false) }
                open(id)
            } catch (e: Exception) {
                _state.update { it.copy(generating = false, error = e.message ?: "The story could not be written.") }
            }
        }
    }
}
