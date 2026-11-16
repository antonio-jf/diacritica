package app.diacritica.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.diacritica.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A public-domain book aligned in two languages (OPUS Books). */
data class BookInfo(val id: Long, val a: Lang, val b: Lang, val title: String, val author: String, val sentences: Int, val words: Int) {
    fun has(l: Lang) = l == a || l == b
    /** About 200 words a minute in a language you're learning. */
    val minutes: Int get() = (words / 200).coerceAtLeast(1)
}

/**
 * The Classics, built by pipeline/build_books.py: a downloaded data pack (files/packs/books.db) or a bundled
 * asset (assets/books/books.db). Optional: until one exists, [available] is false.
 */
class BooksRepository(private val context: Context) {
    val packFile = File(File(context.filesDir, "packs"), "books.db")
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val copied = File(dir, "books.db")
    private val marker = File(dir, "source.txt")
    private val mutex = Mutex()
    @Volatile private var db: SQLiteDatabase? = null

    private fun bundledSize(): Long? = runCatching { context.assets.openFd(ASSET).use { it.length } }.getOrNull()

    fun available(): Boolean = packFile.exists() || bundledSize() != null

    private suspend fun get(): SQLiteDatabase? = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            val file = if (packFile.exists()) packFile else {
                val size = bundledSize() ?: return@withContext null
                if (!copied.exists() || marker.takeIf { it.exists() }?.readText() != "asset:$size") {
                    context.assets.open(ASSET).use { i -> copied.outputStream().use { i.copyTo(it) } }
                    marker.writeText("asset:$size")
                }
                copied
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    suspend fun reload() = mutex.withLock { db?.close(); db = null }

    /** Books, optionally only those in [pair] (either order) and matching [query] in title or author. */
    suspend fun list(pair: Pair<Lang, Lang>? = null, query: String = ""): List<BookInfo> = withContext(Dispatchers.IO) {
        val d = get() ?: return@withContext emptyList()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (pair != null) {
            where += "((lang_a = ? AND lang_b = ?) OR (lang_a = ? AND lang_b = ?))"
            args += listOf(pair.first.code, pair.second.code, pair.second.code, pair.first.code)
        }
        if (query.isNotBlank()) {
            where += "(title LIKE ? OR author LIKE ?)"
            args += listOf("%${query.trim()}%", "%${query.trim()}%")
        }
        val sql = "SELECT id, lang_a, lang_b, title, author, sentences, words FROM book" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY author, title"
        d.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val a = Lang.fromCode(c.getString(1)) ?: continue
                    val b = Lang.fromCode(c.getString(2)) ?: continue
                    add(BookInfo(c.getLong(0), a, b, c.getString(3), c.getString(4), c.getInt(5), c.getInt(6)))
                }
            }
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        val d = get() ?: return@withContext 0
        d.rawQuery("SELECT COUNT(*) FROM book", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** The book's aligned sentences, in order. */
    suspend fun sentences(id: Long): List<Map<Lang, String>> = withContext(Dispatchers.IO) {
        val d = get() ?: return@withContext emptyList()
        val (a, b) = d.rawQuery("SELECT lang_a, lang_b FROM book WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) Lang.fromCode(it.getString(0)) to Lang.fromCode(it.getString(1)) else null to null
        }
        if (a == null || b == null) return@withContext emptyList()
        d.rawQuery("SELECT a, b FROM bsent WHERE book_id = ? ORDER BY idx", arrayOf(id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(mapOf(a to c.getString(0), b to c.getString(1))) }
        }
    }

    companion object { const val ASSET = "books/books.db" }
}
