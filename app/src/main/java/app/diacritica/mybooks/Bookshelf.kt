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
            dao.deleteParagraphs(id); dao.deleteChapters(id); dao.deleteBook(id)
        }
        b?.cover?.let { File(it).delete() }
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

        fun countWords(text: String, lang: Lang): Int =
            if (lang == Lang.ZH) text.count { TextNorm.containsHan(it.toString()) }
            else text.split(Regex("\\s+")).count { w -> w.any(Char::isLetter) }
    }
}
