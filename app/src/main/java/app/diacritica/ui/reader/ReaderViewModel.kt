package app.diacritica.ui.reader

import app.diacritica.settings.TranslateEngine
import app.diacritica.reader.TextImport
import app.diacritica.data.TextNorm
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.combine
import app.diacritica.undo.removeSavedWord
import app.diacritica.undo.deleteStory
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

/** "Explain" for a Reader sentence: Gemini's breakdown, cached as a note under the story. */
data class ReaderExplain(
    val idx: Int,
    val lang: Lang,
    val sentence: String,
    val loading: Boolean = true,
    val breakdown: app.diacritica.mybooks.Breakdown? = null,
    val error: String? = null,
    val lessons: Set<String> = emptySet(),
    val message: String? = null,
)

data class ShelfItem(val story: Story, val titles: Map<Lang, String>)

data class ReaderUiState(
    val story: LoadedStory? = null,
    val highlighted: Int? = null,
    val popup: WordPopup? = null,
    val generating: Boolean = false,
    /** Making a story from pasted text: (sentences translated, total), or null. */
    val importing: Pair<Int, Int>? = null,
    val error: String? = null,
    val message: String? = null,
    val explain: ReaderExplain? = null,
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
        _state.update { it.copy(story = null, highlighted = null, popup = null) }
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


    fun setTapLookup(on: Boolean) { viewModelScope.launch { c.settings.setReaderTapLookup(on) } }
    fun setShowMyWords(on: Boolean) { viewModelScope.launch { c.settings.setReaderShowMyWords(on) } }

    /**
     * Your words in the open story's languages: normalized word form → 1 (learning: saved or in Review)
     * or 2 (known: a mature card). Forms come from the dictionary's tables, so "nahm" counts for nehmen.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val wordMarks: StateFlow<Map<Lang, Map<String, Int>>> = combine(
        c.userDb.saved().all(),
        c.userDb.cards().knowledge(),
        _state.map { it.story?.languages.orEmpty() }.distinctUntilChanged(),
    ) { saved, knowledge, langs -> Triple(saved, knowledge, langs) }
        .mapLatest { (saved, knowledge, langs) ->
            withContext(Dispatchers.IO) {
                val status = HashMap<Pair<Lang, String>, Int>()
                saved.forEach { w -> Lang.fromCode(w.lang)?.takeIf { it in langs }?.let { status.merge(it to w.word, 1) { a, b -> maxOf(a, b) } } }
                knowledge.forEach { k -> Lang.fromCode(k.lang)?.takeIf { it in langs }?.let { status.merge(it to k.word, if (k.known == 1) 2 else 1) { a, b -> maxOf(a, b) } } }
                val out = HashMap<Lang, HashMap<String, Int>>()
                for ((key, v) in status) {
                    val (lang, word) = key
                    val m = out.getOrPut(lang) { HashMap() }
                    val forms = if (lang == Lang.ZH) setOf(word) else runCatching { c.dictionary.surfaceForms(lang, word) }.getOrDefault(setOf(TextNorm.normalize(word)))
                    forms.forEach { f -> m.merge(f, v) { a, b -> maxOf(a, b) } }
                }
                out.mapValues { it.value.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())


    fun delete(id: Long) {
        if (reading.value?.storyId == id) c.readAloud.stop()
        viewModelScope.launch { c.deleteStory(id) }
    }

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
                c.removeSavedWord(w.lang.code, w.word)
            } else {
                val s = c.settings.settings.first()
                val story = _state.value.story
                val ctx = _state.value.popup?.let { p -> story?.sentences?.getOrNull(p.sentenceIdx)?.get(p.lang) }
                c.userDb.saved().save(SavedWord(lang = w.lang.code, word = w.word, note = ctx ?: w.gloss, deck = "Reader"))
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

    // ------------------------------------------------------------------ Explain (breakdown of a sentence)

    private val lessonJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val lessonList = kotlinx.serialization.builtins.ListSerializer(app.diacritica.tests.Lesson.serializer())

    /**
     * Breaks down sentence [idx] in [lang]: the cached breakdown if there is one (stored as a note under −storyId
     * in the bookshelf database), else one Gemini request with the neighbouring sentences as context.
     */
    fun explain(idx: Int, lang: Lang) {
        val story = _state.value.story ?: return
        val sentence = story.sentences.getOrNull(idx)?.get(lang)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        _state.update { it.copy(explain = ReaderExplain(idx, lang, sentence)) }
        viewModelScope.launch {
            val noteKey = -story.story.id
            val cached = runCatching { c.bookshelf.noteFor(noteKey, sentence) }.getOrNull()
            app.diacritica.mybooks.Breakdown.decode(cached?.breakdown)?.let { b ->
                _state.update { st -> st.copy(explain = st.explain?.takeIf { it.sentence == sentence }?.copy(loading = false, breakdown = b, lessons = lessonTitles(cached))) }
                return@launch
            }
            val s = c.settings.settings.first()
            val err = when {
                s.geminiKey.isBlank() -> "Explanations are written by Gemini: add a key in Settings › Rehearse."
                !c.online.isOnline() -> "You're offline. Explanations need the internet."
                else -> null
            }
            if (err != null) { _state.update { st -> st.copy(explain = st.explain?.copy(loading = false, error = err)) }; return@launch }
            val context = (idx - 1..idx + 1).mapNotNull { story.sentences.getOrNull(it)?.get(lang) }.joinToString(if (lang == Lang.ZH) "" else " ")
            try {
                val b = app.diacritica.mybooks.Breakdown.request(c.gemini, s.geminiKey, s.geminiModel, sentence, lang, story.title(Lang.EN), context)
                c.bookshelf.upsertNote(noteKey, 0, idx, sentence) { it.copy(breakdown = app.diacritica.mybooks.Breakdown.encode(b)) }
                _state.update { st -> st.copy(explain = st.explain?.takeIf { it.sentence == sentence }?.copy(loading = false, breakdown = b)) }
            } catch (e: Exception) {
                _state.update { st -> st.copy(explain = st.explain?.copy(loading = false, error = e.message ?: "The explanation failed. Try again.")) }
            }
        }
    }

    fun dismissExplain() = _state.update { it.copy(explain = null) }

    private fun lessonTitles(n: app.diacritica.mybooks.BookNote?): Set<String> =
        n?.lessons?.let { runCatching { lessonJson.decodeFromString(lessonList, it).map { l -> l.title }.toSet() }.getOrNull() }.orEmpty()

    /** A grammar point from the explanation becomes a lesson in Grammar › Lessons, linked to the story. */
    fun makeLesson(point: app.diacritica.mybooks.GrammarPoint) {
        val e = _state.value.explain ?: return
        val b = e.breakdown ?: return
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val lesson = b.lesson(point, e.lang, story.title(Lang.EN), e.sentence)
            c.bookshelf.upsertNote(-story.story.id, 0, e.idx, e.sentence) { n ->
                val list = n.lessons?.let { runCatching { lessonJson.decodeFromString(lessonList, it) }.getOrNull() }.orEmpty()
                n.copy(lessons = lessonJson.encodeToString(lessonList, list.filter { it.title != lesson.title } + lesson))
            }
            _state.update { st -> st.copy(explain = st.explain?.copy(lessons = st.explain.lessons + point.title, message = "Added to Grammar › Lessons.")) }
            // The full lesson (sections, examples, mistakes) is written in the background and kept.
            c.lessons.load()
            c.lessons.write(lesson, quiet = true)
        }
    }

    /** A word from the explanation, saved with the sentence as its context. */
    fun saveVocab(v: app.diacritica.mybooks.VocabItem) {
        val e = _state.value.explain ?: return
        viewModelScope.launch {
            val key = "${e.lang.code}:${v.word}"
            if (key in savedKeys.value) c.removeSavedWord(e.lang.code, v.word)
            else {
                val s = c.settings.settings.first()
                c.userDb.saved().save(SavedWord(lang = e.lang.code, word = v.word, note = e.sentence, deck = "Reader"))
                c.review.ensureCards(e.lang, v.word, s.activeLanguages, s.script)
            }
        }
    }

    fun clearExplainMessage() = _state.update { st -> st.copy(explain = st.explain?.copy(message = null)) }

    /** Writes a new story with Gemini and opens it. */
    /**
     * Makes a story from your own text: detects its language (unless [from] is given), splits it into
     * sentences, translates them into [targets] (Gemini in one request when that's the Translate engine,
     * otherwise on the phone; on-device fills any gaps), saves it on the shelf and opens it.
     */
    fun importText(title: String, text: String, from: Lang?, targets: List<Lang>) {
        if (text.isBlank() || _state.value.importing != null) return
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val src = from ?: c.translator.detect(text) ?: s.readerLangA
            val into = targets.filter { it != src }.ifEmpty { listOf(if (src == Lang.EN) Lang.ES else Lang.EN) }
            val sentences = TextImport.sentences(text.take(TextImport.MAX_CHARS), src)
            if (sentences.isEmpty()) { _state.update { it.copy(error = "No sentences found in that text.") }; return@launch }
            val total = sentences.size * into.size
            _state.update { it.copy(importing = 0 to total, error = null) }
            try {
                var rows: List<Map<Lang, String>> = sentences.map { mapOf(src to it) }
                if (s.translateEngine == TranslateEngine.GEMINI && s.geminiKey.isNotBlank() && c.online.isOnline()) {
                    rows = runCatching { TextImport.translateWithGemini(c.gemini, s.geminiKey, s.geminiModel, src, sentences, into) }.getOrDefault(rows)
                }
                var done = rows.sumOf { r -> into.count { it in r } }
                _state.update { it.copy(importing = done to total) }
                val filled = rows.mapIndexed { i, r ->
                    val m = r.toMutableMap()
                    for (l in into) if (l !in m) {
                        m[l] = c.translator.translate(sentences[i], src, l)
                        done++
                        _state.update { it.copy(importing = done to total) }
                    }
                    m.toMap()
                }
                val id = c.stories.importText(title.trim().ifEmpty { TextImport.titleFrom(sentences) }, listOf(src) + into, filled)
                c.settings.setReaderLangs(src, into.first())
                _state.update { it.copy(importing = null) }
                open(id)
            } catch (e: Exception) {
                _state.update {
                    it.copy(importing = null, error = if (!c.online.isOnline()) "The translation model for one of these languages isn't on the phone yet and you're offline. Connect once to download it (about 30 MB)."
                        else "Couldn't translate the text: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

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
