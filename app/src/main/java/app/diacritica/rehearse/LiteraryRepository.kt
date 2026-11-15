package app.diacritica.rehearse

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.diacritica.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One sentence from a public-domain book, in its original language. */
data class LiterarySentence(val id: Long, val lang: Lang, val text: String, val level: String, val title: String, val author: String) {
    /** "Guy de Maupassant, Contes" (the catalog's life dates are dropped). */
    val credit: String get() {
        val who = author.replace(Regex(",?\\s*\\d{3,4}\\??-\\d{0,4}\\??"), "").trim()
        // Catalog names are "Surname, First": show "First Surname".
        val name = who.split(", ").let { if (it.size == 2) "${it[1]} ${it[0]}" else who }
        return listOf(name, title).filter { it.isNotBlank() }.joinToString(", ")
    }
}

/**
 * Literary sentences built by pipeline/build_literary.py from Project Gutenberg and bundled as
 * assets/literary/literary.db. The asset is optional: until it is built, [available] is false.
 */
class LiteraryRepository(private val context: Context) {
    private val dir = File(context.filesDir, "literary").apply { mkdirs() }
    private val dbFile = File(dir, "literary.db")
    private val marker = File(dir, "source.txt")
    private val mutex = Mutex()
    @Volatile private var db: SQLiteDatabase? = null
    private val recent = ArrayDeque<Long>()

    private fun assetSize(): Long? = runCatching { context.assets.openFd(ASSET).use { it.length } }.getOrNull()

    private suspend fun get(): SQLiteDatabase? = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            val size = assetSize() ?: return@withContext null
            if (!dbFile.exists() || marker.takeIf { it.exists() }?.readText() != "asset:$size") {
                context.assets.open(ASSET).use { i -> dbFile.outputStream().use { i.copyTo(it) } }
                marker.writeText("asset:$size")
            }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    suspend fun available(): Boolean = get() != null

    /** Languages that have sentences. */
    suspend fun languages(): Set<Lang> = withContext(Dispatchers.IO) {
        val d = get() ?: return@withContext emptySet()
        d.rawQuery("SELECT DISTINCT lang FROM lit", null).use { c ->
            buildSet { while (c.moveToNext()) Lang.fromCode(c.getString(0))?.let { add(it) } }
        }
    }

    /** A random sentence in [lang] at one of [levels], avoiding the last 40 shown. */
    suspend fun next(lang: Lang, levels: Set<String>): LiterarySentence? = withContext(Dispatchers.IO) {
        val d = get() ?: return@withContext null
        val lv = levels.ifEmpty { setOf("B1", "B2") }
        val avoid = recent.joinToString(",").ifEmpty { "-1" }
        val ph = lv.joinToString(",") { "?" }
        val sql = "SELECT id, lang, text, level, title, author FROM lit WHERE lang = ? AND level IN ($ph) AND id NOT IN ($avoid) ORDER BY RANDOM() LIMIT 1"
        val row = d.rawQuery(sql, (listOf(lang.code) + lv).toTypedArray()).use { read(it) }
            ?: d.rawQuery("SELECT id, lang, text, level, title, author FROM lit WHERE lang = ? ORDER BY RANDOM() LIMIT 1", arrayOf(lang.code)).use { read(it) }
            ?: return@withContext null
        recent.addLast(row.id); if (recent.size > 40) recent.removeFirst()
        row
    }

    suspend fun byId(id: Long): LiterarySentence? = withContext(Dispatchers.IO) {
        val d = get() ?: return@withContext null
        d.rawQuery("SELECT id, lang, text, level, title, author FROM lit WHERE id = ?", arrayOf(id.toString())).use { read(it) }
    }

    private fun read(c: android.database.Cursor): LiterarySentence? {
        if (!c.moveToFirst()) return null
        val lang = Lang.fromCode(c.getString(1)) ?: return null
        return LiterarySentence(c.getLong(0), lang, c.getString(2), c.getString(3), c.getString(4) ?: "", c.getString(5) ?: "")
    }

    companion object { const val ASSET = "literary/literary.db" }
}
