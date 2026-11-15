package app.diacritica.rehearse

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.diacritica.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One sentence and its human translations (Tatoeba), keyed by language. */
data class SentenceGroup(val id: Long, val level: String, val texts: Map<Lang, List<String>>)

/**
 * Rehearse sentences, built by pipeline/build_sentences.py and bundled as
 * assets/sentences/sentences.db (copied to app storage, re-copied when the bundled file changes).
 */
class SentenceRepository(private val context: Context) {
    private val dir = File(context.filesDir, "sentences").apply { mkdirs() }
    private val dbFile = File(dir, "sentences.db")
    private val marker = File(dir, "source.txt")
    private val mutex = Mutex()
    @Volatile private var db: SQLiteDatabase? = null
    private val recent = ArrayDeque<Long>()

    private suspend fun get(): SQLiteDatabase = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            val size = context.assets.openFd(ASSET).use { it.length }
            if (!dbFile.exists() || marker.takeIf { it.exists() }?.readText() != "asset:$size") {
                context.assets.open(ASSET).use { i -> dbFile.outputStream().use { i.copyTo(it) } }
                marker.writeText("asset:$size")
            }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    /**
     * A random sentence at one of [levels] that has a translation in at least [minLangs] of [active]
     * (other than English). Avoids repeating the last 30.
     */
    suspend fun next(levels: Set<String>, active: List<Lang>, minLangs: Int = 2): SentenceGroup? = withContext(Dispatchers.IO) {
        val d = get()
        val lv = levels.ifEmpty { setOf("A2", "B1", "B2") }
        val placeholders = lv.joinToString(",") { "?" }
        val avoid = recent.joinToString(",").ifEmpty { "-1" }
        val id = d.rawQuery(
            "SELECT id FROM sgroup WHERE level IN ($placeholders) AND langs >= ? AND id NOT IN ($avoid) ORDER BY RANDOM() LIMIT 1",
            (lv.toList() + minLangs.toString()).toTypedArray(),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: d.rawQuery("SELECT id FROM sgroup ORDER BY RANDOM() LIMIT 1", null).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return@withContext null
        recent.addLast(id); if (recent.size > 30) recent.removeFirst()
        load(d, id)
    }

    /**
     * "My words": a sentence that uses one of [words] (language, word) in that language.
     * Words are tried in the given order; returns the sentence and the word it practises.
     */
    suspend fun nextWithWord(words: List<Pair<Lang, String>>, levels: Set<String>): Pair<SentenceGroup, Pair<Lang, String>>? =
        withContext(Dispatchers.IO) {
            val d = get()
            val lv = levels.ifEmpty { setOf("A2", "B1", "B2") }
            val placeholders = lv.joinToString(",") { "?" }
            for ((lang, word) in words) {
                val w = word.trim()
                if (w.length < 2 && lang != Lang.ZH) continue
                // Whole-word match for alphabetic languages (padding with spaces and stripping punctuation); substring for Chinese.
                val (cond, arg) = if (lang == Lang.ZH) "s.text LIKE ?" to "%$w%"
                else "(' ' || lower(replace(replace(replace(replace(replace(s.text, ',', ' '), '.', ' '), '?', ' '), '!', ' '), char(39), ' ')) || ' ') LIKE ?" to "% ${w.lowercase()} %"
                val id = d.rawQuery(
                    "SELECT s.group_id FROM stext s JOIN sgroup g ON g.id = s.group_id WHERE s.lang = ? AND $cond " +
                        "AND g.level IN ($placeholders) ORDER BY RANDOM() LIMIT 1",
                    (listOf(lang.code, arg) + lv).toTypedArray(),
                ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: continue
                if (id in recent) continue
                recent.addLast(id); if (recent.size > 30) recent.removeFirst()
                return@withContext load(d, id) to (lang to w)
            }
            null
        }

    /** A specific sentence group (Weak spots), or null if it's no longer in the data. */
    suspend fun byId(id: Long): SentenceGroup? = withContext(Dispatchers.IO) {
        val d = get()
        val exists = d.rawQuery("SELECT 1 FROM sgroup WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() }
        if (exists) load(d, id) else null
    }

    private fun load(d: SQLiteDatabase, id: Long): SentenceGroup {
        val level = d.rawQuery("SELECT level FROM sgroup WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }
        val texts = LinkedHashMap<Lang, MutableList<String>>()
        d.rawQuery("SELECT lang, text FROM stext WHERE group_id = ? ORDER BY lang, ord", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) {
                val l = Lang.fromCode(c.getString(0)) ?: continue
                texts.getOrPut(l) { mutableListOf() } += c.getString(1)
            }
        }
        return SentenceGroup(id, level, texts)
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        get().rawQuery("SELECT COUNT(*) FROM sgroup", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    companion object { const val ASSET = "sentences/sentences.db" }
}
