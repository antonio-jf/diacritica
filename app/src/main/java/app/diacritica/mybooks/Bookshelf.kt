package app.diacritica.mybooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Your imported EPUBs: importing, reading positions, search and deletion. */
class Bookshelf(private val context: Context, private val detect: suspend (String) -> Lang?) {
    private val db by lazy { BookshelfDatabase.create(context) }
    private val dao get() = db.dao()
    private val coverDir get() = File(context.filesDir, "bookshelf").apply { mkdirs() }

    fun all(): Flow<List<MyBook>> = dao.all()
    suspend fun book(id: Long) = dao.book(id)
    suspend fun chapters(id: Long) = dao.chapters(id)
    suspend fun paragraphs(id: Long, chapter: Int) = dao.paragraphs(id, chapter)
    suspend fun search(id: Long, q: String) = if (q.isBlank()) emptyList() else dao.search(id, q.trim())

    suspend fun setPosition(id: Long, chapter: Int, paragraph: Int, chapters: List<BookChapter>, total: Int) {
        val start = chapters.getOrNull(chapter)?.start ?: 0
        val progress = if (total <= 0) 0f else ((start + paragraph + 1).toFloat() / total).coerceIn(0f, 1f)
        dao.setPosition(id, chapter, paragraph, progress)
    }

    suspend fun delete(id: Long) {
        val b = dao.book(id)
        db.withTransaction {
            dao.deleteParagraphs(id); dao.deleteChapters(id); dao.deleteNotes(id); dao.deleteMarks(id); dao.deleteReads(id); dao.deleteBook(id)
        }
        b?.cover?.let { File(it).delete() }
    }

    // ------------------------------------------------------------------ notes on passages

    fun notes(bookId: Long) = dao.notes(bookId)
    suspend fun note(id: Long) = dao.note(id)
    suspend fun noteFor(bookId: Long, passage: String) = dao.noteFor(bookId, passage)
    suspend fun deleteNote(id: Long) = dao.deleteNote(id)

    /** Creates or updates the note for [passage] (one per passage), changing only what [change] sets. */
    suspend fun upsertNote(bookId: Long, chapter: Int, paragraph: Int, passage: String, change: (BookNote) -> BookNote): BookNote {
        val base = dao.noteFor(bookId, passage) ?: BookNote(bookId = bookId, chapter = chapter, paragraph = paragraph, passage = passage)
        val n = change(base)
        val id = dao.saveNote(n)
        return n.copy(id = if (n.id == 0L) id else n.id)
    }

    /**
     * Lessons made from book passages, for the Grammar section: (lesson, note, title). Notes with a negative
     * bookId belong to Reader stories (−storyId); [storyTitle] names them.
     */
    suspend fun lessons(storyTitle: suspend (Long) -> String? = { null }): List<Triple<app.diacritica.tests.Lesson, BookNote, String>> {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val titles = HashMap<Long, String>()
        return dao.notesWithLessons().flatMap { n ->
            val list = runCatching {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(app.diacritica.tests.Lesson.serializer()), n.lessons!!)
            }.getOrDefault(emptyList())
            val title = titles.getOrPut(n.bookId) {
                if (n.bookId < 0) storyTitle(-n.bookId) ?: "a Reader story" else dao.book(n.bookId)?.title ?: "a book"
            }
            list.map { Triple(it, n, title) }
        }
    }

    // ------------------------------------------------------------------ bookmarks

    fun marks(bookId: Long) = dao.marks(bookId)
    suspend fun deleteMark(id: Long) = dao.deleteMark(id)

    /** Adds a bookmark at the paragraph, or removes the one that's there. Returns true if one was added. */
    suspend fun toggleMark(bookId: Long, chapter: Int, paragraph: Int, label: String, existing: Boolean): Boolean {
        if (existing) { dao.deleteMarkAt(bookId, chapter, paragraph); return false }
        dao.addMark(BookMark(bookId = bookId, chapter = chapter, paragraph = paragraph, label = label))
        return true
    }

    // ------------------------------------------------------------------ reading log (Progress)

    /** Counts [words] as read today and moves the furthest point to [furthest]. */
    suspend fun recordRead(book: MyBook, words: Int, furthest: Int) {
        if (words > 0) {
            val day = java.time.LocalDate.now().toEpochDay()
            val prev = dao.readOn(book.id, day)?.words ?: 0
            dao.saveRead(BookRead(book.id, day, book.lang, prev + words))
        }
        dao.setFurthest(book.id, furthest)
    }

    data class Stats(val words: List<LangWords>, val days: Int, val reading: List<MyBook>, val finished: Int)

    /** Reading since [fromDay] (epoch day; 0 = all time), plus books in progress. */
    suspend fun stats(fromDay: Long): Stats = withContext(Dispatchers.IO) {
        val opened = dao.openedBooks()
        Stats(
            words = dao.wordsSince(fromDay),
            days = dao.daysReadSince(fromDay),
            reading = opened.filter { it.progress < 0.98f }.sortedByDescending { it.openedAt },
            finished = opened.count { it.progress >= 0.98f },
        )
    }

    // ------------------------------------------------------------------ Rehearse

    data class ReadPassage(val bookId: Long, val title: String, val lang: Lang, val chapter: Int, val paragraph: Int, val text: String)

    /**
     * [n] consecutive sentences from a part of one of your books you've already read (up to the furthest point),
     * for Rehearse's "From my reading". Null if no book has been read yet.
     */
    suspend fun readPassage(n: Int, langs: Set<Lang>): ReadPassage? = withContext(Dispatchers.IO) {
        val books = dao.openedBooks().filter { b -> Lang.fromCode(b.lang)?.let { it in langs } == true }
        if (books.isEmpty()) return@withContext null
        repeat(10) {
            val b = books.random()
            val lang = Lang.fromCode(b.lang) ?: return@repeat
            val chapters = dao.chapters(b.id).ifEmpty { return@repeat }
            val reached = maxOf(b.furthest, (chapters.getOrNull(b.chapter)?.start ?: 0) + b.paragraph)
            val g = (0..reached).random()
            val ch = chapters.lastOrNull { it.start <= g } ?: return@repeat
            val p = dao.paragraph(b.id, ch.idx, g - ch.start) ?: return@repeat
            if (p.heading) return@repeat
            val sentences = splitSentences(p.text, lang)
            if (sentences.isEmpty()) return@repeat
            val k = n.coerceIn(1, sentences.size)
            val start = (0..sentences.size - k).random()
            val text = sentences.subList(start, start + k).joinToString(if (lang == Lang.ZH) "" else " ")
            return@withContext ReadPassage(b.id, b.title, lang, ch.idx, p.idx, text)
        }
        null
    }

    class ImportResult(val id: Long, val alreadyHad: Boolean)

    /** Reads an EPUB from [uri] and stores it. Throws [EpubException] with a readable message on failure. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = fileName(uri)
        val book = context.contentResolver.openInputStream(uri)?.use { EpubParser.parse(it) }
            ?: throw EpubException("Couldn't open that file.")
        val sample = book.chapters.asSequence().flatMap { it.paragraphs.asSequence() }.filter { !it.heading }
            .map { it.text }.joinToString(" ").take(3000)
        val lang = langOf(book.language) ?: runCatching { detect(sample) }.getOrNull()
            ?: throw EpubException(
                "This book's language isn't one Diacritica supports" + (book.language?.let { " ($it)" } ?: "") +
                    ". It works with English, French, German, Italian, Spanish and Mandarin.",
            )
        dao.sameBook(book.title, lang.code)?.let { return@withContext ImportResult(it.id, alreadyHad = true) }

        var start = 0
        var words = 0
        val chapters = ArrayList<BookChapter>()
        val paragraphs = ArrayList<BookParagraph>()
        book.chapters.forEachIndexed { ci, ch ->
            chapters += BookChapter(0, ci, ch.title, start, ch.paragraphs.size)
            ch.paragraphs.forEachIndexed { pi, p ->
                paragraphs += BookParagraph(0, ci, pi, p.text, p.heading)
                words += countWords(p.text, lang)
            }
            start += ch.paragraphs.size
        }
        val id = db.withTransaction {
            val id = dao.insertBook(
                MyBook(title = book.title, author = book.author, lang = lang.code, chapters = chapters.size,
                    paragraphs = start, words = words, cover = null, fileName = name),
            )
            dao.insertChapters(chapters.map { it.copy(bookId = id) })
            paragraphs.chunked(2000).forEach { part -> dao.insertParagraphs(part.map { it.copy(bookId = id) }) }
            id
        }
        // The cover, scaled down: it's only shown as a thumbnail.
        book.cover?.let { bytes -> saveCover(id, bytes)?.let { dao.setCover(id, it) } }
        ImportResult(id, alreadyHad = false)
    }

    private fun saveCover(id: Long, bytes: ByteArray): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 360) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val f = File(coverDir, "$id.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f.absolutePath
    }.getOrNull()

    private fun fileName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        /** "fr-FR", "zh-Hans", "cmn", "ger" → a supported language. */
        fun langOf(tag: String?): Lang? {
            val t = tag?.trim()?.lowercase()?.replace('_', '-') ?: return null
            val head = t.substringBefore('-')
            return when (head) {
                "zh", "cmn", "chi", "zho" -> Lang.ZH
                "en", "eng" -> Lang.EN
                "fr", "fre", "fra" -> Lang.FR
                "de", "ger", "deu" -> Lang.DE
                "it", "ita" -> Lang.IT
                "es", "spa" -> Lang.ES
                else -> null
            }
        }

        /** Sentences of a paragraph worth translating: at least a few words (or characters), not too long. */
        fun splitSentences(text: String, lang: Lang): List<String> {
            val it = java.text.BreakIterator.getSentenceInstance(lang.locale)
            val flat = text.replace('\n', ' ')
            it.setText(flat)
            val out = ArrayList<String>()
            var start = it.first()
            var end = it.next()
            while (end != java.text.BreakIterator.DONE) {
                out += cleanSentence(flat.substring(start, end))
                start = end; end = it.next()
            }
            return out.filter { s ->
                val size = countWords(s, lang)
                if (lang == Lang.ZH) size in 4..60 else size in 3..35
            }
        }

        /** Trims a closing quote the splitter left at the start (« Oui. » Il…) or an opening one at the end. */
        internal fun cleanSentence(s: String): String =
            s.trim().trimStart('»', '”', '’', ')', ']', ' ').trimEnd('«', '“', '‘', '(', '[', ' ').trim()

        fun countWords(text: String, lang: Lang): Int =
            if (lang == Lang.ZH) text.count { TextNorm.containsHan(it.toString()) }
            else text.split(Regex("\\s+")).count { w -> w.any(Char::isLetter) }
    }
}
