package app.diacritica.ui.books

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.DictionaryRepository
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.data.TextNorm
import app.diacritica.data.WordInfo
import app.diacritica.data.user.HistoryItem
import app.diacritica.data.user.SavedWord
import app.diacritica.mybooks.BookChapter
import app.diacritica.mybooks.BookParagraph
import app.diacritica.mybooks.BookSearchHit
import app.diacritica.mybooks.EpubException
import app.diacritica.mybooks.MyBook
import app.diacritica.undo.removeSavedWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val importing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** Set after an import, so the screen opens the book. */
    val openId: Long? = null,
)

data class BookReaderState(
    val book: MyBook? = null,
    val chapters: List<BookChapter> = emptyList(),
    val chapter: Int = 0,
    val paragraphs: List<BookParagraph> = emptyList(),
    /** Paragraph to scroll to when the chapter appears; bumps [jump] so the screen scrolls again. */
    val scrollTo: Int = 0,
    val jump: Int = 0,
    val loading: Boolean = true,
    val searchResults: List<BookSearchHit>? = null,
    /** A short confirmation shown above the bottom of the page. */
    val message: String? = null,
)

/** Selected text: a character range [start, end) in one paragraph of the current chapter. */
data class Selection(val chapter: Int, val paragraph: Int, val start: Int, val end: Int)

/** The passage panel: a translation or a breakdown of the selected (or a saved) passage. */
data class PassagePanel(
    val passage: String,
    val chapter: Int,
    val paragraph: Int,
    val breakdownMode: Boolean,
    val target: Lang = Lang.EN,
    val translating: Boolean = false,
    val translation: String? = null,
    val loading: Boolean = false,
    val breakdown: app.diacritica.mybooks.Breakdown? = null,
    val error: String? = null,
    /** The saved note, once there is one. */
    val noteId: Long? = null,
    /** Titles of grammar points already turned into lessons. */
    val lessons: Set<String> = emptySet(),
    val message: String? = null,
)

/** A tapped word: the dictionary's answer and the sentence it came from (saved with the word). */
data class BookWord(
    val lang: Lang,
    val token: String,
    val sentence: String,
    val loading: Boolean = true,
    val result: LookupResult? = null,
    val fetching: Boolean = false,
)

class BooksViewModel(private val c: AppContainer) : ViewModel() {
    val books: StateFlow<List<MyBook>> = c.bookshelf.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _reader = MutableStateFlow(BookReaderState())
    val reader: StateFlow<BookReaderState> = _reader.asStateFlow()

    private val _word = MutableStateFlow<BookWord?>(null)
    val word: StateFlow<BookWord?> = _word.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _panel = MutableStateFlow<PassagePanel?>(null)
    val panel: StateFlow<PassagePanel?> = _panel.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<app.diacritica.mybooks.BookNote>> = _reader.map { it.book?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.bookshelf.notes(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedKeys: StateFlow<Set<String>> = c.userDb.saved().keys().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // ------------------------------------------------------------------ library

    fun import(uri: Uri) {
        if (_library.value.importing) return
        _library.update { it.copy(importing = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val r = c.bookshelf.import(uri)
                _library.update {
                    it.copy(importing = false, openId = r.id, message = if (r.alreadyHad) "You already have this book; opening it." else null)
                }
            } catch (e: EpubException) {
                _library.update { it.copy(importing = false, error = e.message) }
            } catch (e: Exception) {
                _library.update { it.copy(importing = false, error = "Couldn't import that file (${e.message ?: e.javaClass.simpleName}).") }
            }
        }
    }

    fun opened() = _library.update { it.copy(openId = null) }
    fun clearMessage() = _library.update { it.copy(error = null, message = null) }

    fun delete(id: Long) { viewModelScope.launch { c.bookshelf.delete(id) } }

    // ------------------------------------------------------------------ reading

    fun open(id: Long) {
        if (_reader.value.book?.id == id && !_reader.value.loading) return
        _word.value = null
        _selection.value = null
        _panel.value = null
        _reader.value = BookReaderState(loading = true)
        viewModelScope.launch {
            val book = c.bookshelf.book(id) ?: run { _reader.update { it.copy(loading = false) }; return@launch }
            val chapters = c.bookshelf.chapters(id)
            val ch = book.chapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            val paras = c.bookshelf.paragraphs(id, ch)
            _reader.value = BookReaderState(book, chapters, ch, paras, scrollTo = book.paragraph.coerceIn(0, (paras.size - 1).coerceAtLeast(0)), loading = false)
            c.bookshelf.setPosition(id, ch, book.paragraph, chapters, book.paragraphs)
        }
    }

    fun goToChapter(index: Int, paragraph: Int = 0) {
        val st = _reader.value
        val book = st.book ?: return
        if (index !in st.chapters.indices) return
        viewModelScope.launch {
            val paras = if (index == st.chapter) st.paragraphs else c.bookshelf.paragraphs(book.id, index)
            _selection.value = null
            _reader.update { it.copy(chapter = index, paragraphs = paras, scrollTo = paragraph.coerceIn(0, (paras.size - 1).coerceAtLeast(0)), jump = it.jump + 1, searchResults = null) }
            savePosition(paragraph)
        }
    }

    private var saveJob: Job? = null

    /** The first paragraph on screen; saved a moment after scrolling stops. */
    fun visible(paragraph: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(700); savePosition(paragraph) }
    }

    private suspend fun savePosition(paragraph: Int) {
        val st = _reader.value
        val book = st.book ?: return
        c.bookshelf.setPosition(book.id, st.chapter, paragraph, st.chapters, book.paragraphs)
    }

    fun search(q: String) {
        val book = _reader.value.book ?: return
        viewModelScope.launch {
            val hits = c.bookshelf.search(book.id, q)
            _reader.update { it.copy(searchResults = hits) }
        }
    }

    fun closeSearch() = _reader.update { it.copy(searchResults = null) }
    fun clearReaderMessage() = _reader.update { it.copy(message = null) }

    fun setText(size: Int? = null, spacing: Float? = null, serif: Boolean? = null) {
        viewModelScope.launch { c.settings.setBookText(size, spacing, serif) }
    }

    fun setPinyinMode(mode: app.diacritica.settings.PinyinMode, top: Int? = null) {
        viewModelScope.launch { c.settings.setReaderPinyinMode(mode, top) }
    }

    fun isRare(word: String, top: Int): Boolean = c.zhLexicon.hasRareChar(word, top)

    private val zhCache = java.util.concurrent.ConcurrentHashMap<String, List<DictionaryRepository.ZhToken>>()

    suspend fun zhTokens(text: String): List<DictionaryRepository.ZhToken> =
        zhCache[text] ?: runCatching { c.dictionary.segmentZh(text) }.getOrNull()
            ?.also { if (it.isNotEmpty()) zhCache[text] = it }
            ?: text.map { DictionaryRepository.ZhToken(it.toString(), null, null) }

    /**
     * Your words in the open book's language: normalized form → 1 (learning) or 2 (known). Forms come from the
     * dictionary's tables, so "nahm" counts for nehmen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val wordMarks: StateFlow<Map<String, Int>> = combine(
        c.userDb.saved().all(),
        c.userDb.cards().knowledge(),
        _reader.map { it.book?.lang }.distinctUntilChanged(),
    ) { saved, knowledge, code -> Triple(saved, knowledge, code) }
        .mapLatest { (saved, knowledge, code) ->
            val lang = code?.let { Lang.fromCode(it) } ?: return@mapLatest emptyMap()
            withContext(Dispatchers.IO) {
                val status = HashMap<String, Int>()
                saved.filter { it.lang == lang.code }.forEach { status.merge(it.word, 1) { a, b -> maxOf(a, b) } }
                knowledge.filter { it.lang == lang.code }.forEach { status.merge(it.word, if (it.known == 1) 2 else 1) { a, b -> maxOf(a, b) } }
                val out = HashMap<String, Int>()
                for ((word, v) in status) {
                    val forms = if (lang == Lang.ZH) setOf(word)
                    else runCatching { c.dictionary.surfaceForms(lang, word) }.getOrDefault(setOf(TextNorm.normalize(word)))
                    forms.forEach { f -> out.merge(f, v) { a, b -> maxOf(a, b) } }
                }
                out
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ------------------------------------------------------------------ passages

    private fun bookLang(): Lang? = _reader.value.book?.lang?.let { Lang.fromCode(it) }

    /** Long-press: select [start, end) of a paragraph (a sentence). */
    fun select(paragraph: Int, start: Int, end: Int) {
        _word.value = null
        _selection.value = Selection(_reader.value.chapter, paragraph, start, end)
    }

    /**
     * Tap while selecting: a word outside the selection extends it to that word; a word inside trims the nearer end
     * back to it. A tap in another paragraph starts over there.
     */
    fun adjust(paragraph: Int, start: Int, end: Int) {
        val sel = _selection.value ?: return select(paragraph, start, end)
        if (sel.paragraph != paragraph || sel.chapter != _reader.value.chapter) return select(paragraph, start, end)
        _selection.value = when {
            start < sel.start -> sel.copy(start = start)
            end > sel.end -> sel.copy(end = end)
            // Inside: move whichever edge is closer to the tapped word.
            start - sel.start <= sel.end - end -> sel.copy(start = end.coerceAtMost(sel.end))
            else -> sel.copy(end = start.coerceAtLeast(sel.start))
        }.let { if (it.end <= it.start) sel else it }
    }

    fun clearSelection() { _selection.value = null }

    fun selectedText(): String? {
        val sel = _selection.value ?: return null
        val p = _reader.value.paragraphs.getOrNull(sel.paragraph)?.text ?: return null
        return p.substring(sel.start.coerceIn(0, p.length), sel.end.coerceIn(0, p.length)).trim().takeIf { it.isNotEmpty() }
    }

    private fun defaultTarget(s: app.diacritica.settings.AppSettings, from: Lang): Lang =
        listOf(Lang.EN, Lang.ES).firstOrNull { it != from && it in s.activeLanguages } ?: s.activeLanguages.firstOrNull { it != from } ?: Lang.EN

    /** Opens the panel for the selection: a translation ([breakdown] false) or a Gemini breakdown. */
    fun openPanel(breakdown: Boolean) {
        val sel = _selection.value ?: return
        val text = selectedText() ?: return
        val book = _reader.value.book ?: return
        viewModelScope.launch {
            val note = c.bookshelf.noteFor(book.id, text)
            val s = c.settings.settings.first()
            val from = bookLang() ?: return@launch
            _panel.value = PassagePanel(
                passage = text, chapter = sel.chapter, paragraph = sel.paragraph, breakdownMode = breakdown,
                target = defaultTarget(s, from),
                translation = note?.translation,
                breakdown = app.diacritica.mybooks.Breakdown.decode(note?.breakdown),
                noteId = note?.id, lessons = lessonTitles(note),
            )
            if (breakdown) breakDown() else if (note?.translation == null) translate(_panel.value!!.target)
        }
    }

    /** Opens a saved note's panel (from the Notes list). */
    fun openNote(n: app.diacritica.mybooks.BookNote) {
        val b = app.diacritica.mybooks.Breakdown.decode(n.breakdown)
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val from = bookLang() ?: Lang.EN
            _panel.value = PassagePanel(
                passage = n.passage, chapter = n.chapter, paragraph = n.paragraph, breakdownMode = b != null,
                target = defaultTarget(s, from), translation = n.translation, breakdown = b, noteId = n.id, lessons = lessonTitles(n),
            )
        }
    }

    fun closePanel() { _panel.value = null }

    fun showBreakdown(on: Boolean) {
        val p = _panel.value ?: return
        _panel.value = p.copy(breakdownMode = on)
        if (on && p.breakdown == null && !p.loading) breakDown()
        if (!on && p.translation == null && !p.translating) translate(p.target)
    }

    /** On-device translation (ML Kit), so it works offline and costs nothing. */
    fun translate(target: Lang) {
        val p = _panel.value ?: return
        val from = bookLang() ?: return
        _panel.value = p.copy(target = target, translating = true, translation = null, error = null)
        viewModelScope.launch {
            val t = runCatching { c.translator.translate(p.passage, from, target) }
            _panel.update { cur ->
                if (cur?.passage != p.passage) cur
                else t.fold(
                    { cur.copy(translating = false, translation = it) },
                    { e -> cur.copy(translating = false, error = "Couldn't translate on this device (${e.message ?: "model not available"}).") },
                )
            }
        }
    }

    fun breakDown() {
        val p = _panel.value ?: return
        val book = _reader.value.book ?: return
        val lang = bookLang() ?: return
        if (p.breakdown != null) return
        if (p.passage.length > app.diacritica.mybooks.Breakdown.MAX_CHARS) {
            _panel.value = p.copy(error = "That's a long selection. Break down up to about ${app.diacritica.mybooks.Breakdown.MAX_CHARS} characters at a time.")
            return
        }
        _panel.value = p.copy(loading = true, error = null)
        viewModelScope.launch {
            val s = c.settings.settings.first()
            if (s.geminiKey.isBlank()) {
                _panel.update { it?.copy(loading = false, error = "Breakdowns are written by Gemini: add a key in Settings › Rehearse.") }
                return@launch
            }
            if (!c.online.isOnline()) {
                _panel.update { it?.copy(loading = false, error = "You're offline. Breakdowns need the internet; translation works offline.") }
                return@launch
            }
            val context = _reader.value.paragraphs.getOrNull(p.paragraph)?.text
            try {
                val b = app.diacritica.mybooks.Breakdown.request(c.gemini, s.geminiKey, s.geminiModel, p.passage, lang, book.title, context)
                // Saved straight away: the same passage never needs a second request.
                val note = c.bookshelf.upsertNote(book.id, p.chapter, p.paragraph, p.passage) { it.copy(breakdown = app.diacritica.mybooks.Breakdown.encode(b)) }
                _panel.update { cur -> if (cur?.passage == p.passage) cur.copy(loading = false, breakdown = b, noteId = note.id) else cur }
            } catch (e: Exception) {
                _panel.update { it?.copy(loading = false, error = e.message ?: "The breakdown failed. Try again.") }
            }
        }
    }

    fun savePassage() {
        val p = _panel.value ?: return
        val book = _reader.value.book ?: return
        viewModelScope.launch {
            val note = c.bookshelf.upsertNote(book.id, p.chapter, p.paragraph, p.passage) { n ->
                n.copy(translation = p.translation ?: n.translation)
            }
            _panel.update { it?.copy(noteId = note.id, message = "Saved to this book's notes.") }
        }
    }

    /** Saves the selection as a note without opening anything. */
    fun saveSelection() {
        val sel = _selection.value ?: return
        val text = selectedText() ?: return
        val book = _reader.value.book ?: return
        viewModelScope.launch {
            c.bookshelf.upsertNote(book.id, sel.chapter, sel.paragraph, text) { it }
            _selection.value = null
            _reader.update { it.copy(message = "Passage saved to this book's notes.") }
        }
    }

    fun deleteNote(id: Long) { viewModelScope.launch { c.bookshelf.deleteNote(id) } }

    private val lessonJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val lessonList = kotlinx.serialization.builtins.ListSerializer(app.diacritica.tests.Lesson.serializer())

    private fun lessonTitles(n: app.diacritica.mybooks.BookNote?): Set<String> =
        n?.lessons?.let { runCatching { lessonJson.decodeFromString(lessonList, it).map { l -> l.title }.toSet() }.getOrNull() }.orEmpty()

    /** Turns a grammar point into a lesson in the Grammar section, linked to this passage. */
    fun makeLesson(point: app.diacritica.mybooks.GrammarPoint) {
        val p = _panel.value ?: return
        val b = p.breakdown ?: return
        val book = _reader.value.book ?: return
        val lang = bookLang() ?: return
        viewModelScope.launch {
            val lesson = b.lesson(point, lang, book.title, p.passage)
            c.bookshelf.upsertNote(book.id, p.chapter, p.paragraph, p.passage) { n ->
                val list = n.lessons?.let { runCatching { lessonJson.decodeFromString(lessonList, it) }.getOrNull() }.orEmpty()
                n.copy(lessons = lessonJson.encodeToString(lessonList, list.filter { it.title != lesson.title } + lesson))
            }
            _panel.update { it?.copy(lessons = it.lessons + point.title, message = "Added to Grammar › Lessons.") }
        }
    }

    /** A word from the breakdown's vocabulary, saved like a tapped word (with the passage as its context). */
    fun saveVocab(v: app.diacritica.mybooks.VocabItem) {
        val lang = bookLang() ?: return
        val p = _panel.value ?: return
        viewModelScope.launch {
            val key = "${lang.code}:${v.word}"
            if (key in savedKeys.value) {
                c.removeSavedWord(lang.code, v.word)
            } else {
                val s = c.settings.settings.first()
                c.userDb.saved().save(SavedWord(lang = lang.code, word = v.word, note = p.passage.replace('\n', ' ')))
                c.review.ensureCards(lang, v.word, s.activeLanguages, s.script)
            }
        }
    }

    fun clearPanelMessage() = _panel.update { it?.copy(message = null) }

    // ------------------------------------------------------------------ words

    fun lookUp(rawToken: String, sentence: String) {
        val lang = _reader.value.book?.lang?.let { Lang.fromCode(it) } ?: return
        val token = rawToken.trim().trim('\'', '’', '-', '«', '»', '“', '”', '„', '"', '—')
        if (token.isEmpty()) return
        _word.value = BookWord(lang, token, sentence)
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val langs = (listOf(lang, Lang.EN) + s.activeLanguages).distinct()
            suspend fun run() = runCatching { c.dictionary.lookup(token, lang, langs, s.script) }.getOrNull()
            var r = run()
            _word.update { w -> if (w?.token == token) w.copy(loading = false, result = r) else w }
            val cedictOnly = r != null && r.concepts.isNotEmpty() && r.concepts.all { c.dictionary.isCedictOnly(it) }
            if ((r == null || r.concepts.isEmpty() || cedictOnly) && s.onlineLookup) {
                _word.update { w -> if (w?.token == token) w.copy(fetching = true) else w }
                val outcome = runCatching { c.online.fetchWord(token, lang, langs).first }.getOrNull()
                if (outcome == app.diacritica.data.online.OnlineLookup.Outcome.FOUND) r = run()
                _word.update { w -> if (w?.token == token) w.copy(fetching = false, result = r) else w }
            }
            // Like a Dictionary search: feeds "My words" and Review.
            if (r != null && r.concepts.isNotEmpty()) {
                c.userDb.history().deleteSame(token, lang.code)
                c.userDb.history().insert(HistoryItem(query = token, lang = lang.code))
            }
        }
    }

    fun dismissWord() { _word.value = null }

    fun toggleSave(w: WordInfo) {
        viewModelScope.launch {
            val key = "${w.lang.code}:${w.word}"
            if (key in savedKeys.value) {
                c.removeSavedWord(w.lang.code, w.word)
            } else {
                val s = c.settings.settings.first()
                val ctx = _word.value?.sentence?.takeIf { it.isNotBlank() }
                c.userDb.saved().save(SavedWord(lang = w.lang.code, word = w.word, note = ctx ?: w.gloss))
                c.review.ensureCards(w.lang, w.word, s.activeLanguages, s.script)
            }
        }
    }

    fun speakWord(w: WordInfo) {
        val text = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
        viewModelScope.launch { c.speaker.speak(text, w.lang, c.settings.settings.first().speechRate, false) }
    }
}
