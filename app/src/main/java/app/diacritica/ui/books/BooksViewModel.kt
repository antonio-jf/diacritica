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
