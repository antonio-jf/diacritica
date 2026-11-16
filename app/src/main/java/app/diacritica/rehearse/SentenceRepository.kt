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
 * Aligned Rehearse sentences bundled as an asset database (copied to app storage, re-copied when the
 * bundled file changes):
 *  - assets/sentences/sentences.db: Tatoeba, built by pipeline/build_sentences.py (always present)
 *  - corpora.db: News, Formal and Encyclopedic from OPUS (pipeline/build_corpora.py). Not bundled in the APK:
 *    it comes as the "corpora" data pack (files/packs/corpora.db); groups carry a `source` column
 */
class SentenceRepository(private val context: Context, private val asset: String = ASSET) {
    private val dir = File(context.filesDir, asset.substringBefore('/')).apply { mkdirs() }
    private val dbFile = File(dir, asset.substringAfterLast('/'))
    private val marker = File(dir, "source.txt")
    private val mutex = Mutex()
    @Volatile private var db: SQLiteDatabase? = null
    private val recent = ArrayDeque<Long>()

    /** A downloaded data pack replaces the bundled asset: files/packs/<name>.db. */
    val packFile = File(File(context.filesDir, "packs"), asset.substringAfterLast('/'))

    private suspend fun get(): SQLiteDatabase = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            if (packFile.exists()) return@withContext SQLiteDatabase.openDatabase(packFile.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
            val size = context.assets.openFd(asset).use { it.length }
            if (!dbFile.exists() || marker.takeIf { it.exists() }?.readText() != "asset:$size") {
                context.assets.open(asset).use { i -> dbFile.outputStream().use { i.copyTo(it) } }
                marker.writeText("asset:$size")
            }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    /** False when neither a data pack nor the bundled asset exists (optional databases). */
    fun available(): Boolean = packFile.exists() || bundled()

    fun bundled(): Boolean = runCatching { context.assets.openFd(asset).use { true } }.getOrDefault(false)

    /** Closes the database so the next query opens the newly installed (or removed) pack. */
    suspend fun reload() = mutex.withLock { db?.close(); db = null; ranked = null }

    /** Sentence groups per source ("news", "formal"…; Tatoeba has a single source). Empty if unavailable. */
    suspend fun countsBySource(): Map<String, Int> = withContext(Dispatchers.IO) {
        if (!available()) return@withContext emptyMap()
        val d = get()
        runCatching {
            d.rawQuery("SELECT source, COUNT(*) FROM sgroup GROUP BY source", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
            }
        }.getOrElse { mapOf("tatoeba" to d.rawQuery("SELECT COUNT(*) FROM sgroup", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }) }
    }

    /** True when groups carry `rank` and `words` (the Tatoeba bank); the corpora only have CEFR tags. */
    @Volatile private var ranked: Boolean? = null
    private fun hasRank(d: SQLiteDatabase): Boolean = ranked ?: runCatching {
        d.rawQuery("PRAGMA table_info(sgroup)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }.contains("rank")
    }.getOrDefault(false).also { ranked = it }

    /**
     * Filters for [band], from tightest to loosest: rank and length window (where available), the band's CEFR
     * tags, then anything. Each is (SQL condition on `g`, args).
     */
    private fun filters(d: SQLiteDatabase, band: Difficulty.Band): List<Pair<String, List<String>>> = buildList {
        if (hasRank(d)) {
            add("g.rank BETWEEN ? AND ? AND g.words BETWEEN ? AND ?" to listOf(band.minRank, band.maxRank, band.minWords, band.maxWords).map { it.toString() })
            // Same vocabulary window, any length.
            add("g.rank BETWEEN ? AND ?" to listOf(band.minRank.toString(), band.maxRank.toString()))
        }
        add("g.level IN (${band.cefr.joinToString(",") { "?" }})" to band.cefr.toList())
        add("1" to emptyList())
    }

    /**
     * A random sentence at difficulty [band] that has a translation in at least [minLangs] of [active]
     * (other than English). Avoids repeating the last 30.
     */
    suspend fun next(band: Difficulty.Band, active: List<Lang>, minLangs: Int = 2, source: String? = null): SentenceGroup? = withContext(Dispatchers.IO) {
        val d = get()
        val avoid = recent.joinToString(",").ifEmpty { "-1" }
        val bySource = if (source != null) " AND g.source = ?" else ""
        var id: Long? = null
        for ((cond, args) in filters(d, band)) {
            id = d.rawQuery(
                "SELECT g.id FROM sgroup g WHERE $cond AND g.langs >= ? AND g.id NOT IN ($avoid)$bySource ORDER BY RANDOM() LIMIT 1",
                (args + minLangs.toString() + listOfNotNull(source)).toTypedArray(),
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
            if (id != null) break
        }
        id = id ?: d.rawQuery("SELECT g.id FROM sgroup g WHERE 1$bySource ORDER BY RANDOM() LIMIT 1", listOfNotNull(source).toTypedArray())
            .use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return@withContext null
        recent.addLast(id); if (recent.size > 30) recent.removeFirst()
        load(d, id)
    }

    /**
     * "My words": a sentence that uses one of [words] (language, word) in that language, near difficulty [band].
     * Words are tried in the given order; returns the sentence and the word it practises.
     */
    suspend fun nextWithWord(words: List<Pair<Lang, String>>, band: Difficulty.Band): Pair<SentenceGroup, Pair<Lang, String>>? =
        withContext(Dispatchers.IO) {
            val d = get()
            // Your own words matter more than the exact difficulty: only the CEFR window, then anything.
            val levelFilters = filters(d, band).drop(if (hasRank(d)) 1 else 0)
            for ((lang, word) in words) {
                val w = word.trim()
                if (w.length < 2 && lang != Lang.ZH) continue
                // Whole-word match for alphabetic languages (padding with spaces and stripping punctuation); substring for Chinese.
                val (cond, arg) = if (lang == Lang.ZH) "s.text LIKE ?" to "%$w%"
                else "(' ' || lower(replace(replace(replace(replace(replace(s.text, ',', ' '), '.', ' '), '?', ' '), '!', ' '), char(39), ' ')) || ' ') LIKE ?" to "% ${w.lowercase()} %"
                var id: Long? = null
                for ((lc, largs) in levelFilters.take(2)) {
                    id = d.rawQuery(
                        "SELECT s.group_id FROM stext s JOIN sgroup g ON g.id = s.group_id WHERE s.lang = ? AND $cond AND $lc ORDER BY RANDOM() LIMIT 1",
                        (listOf(lang.code, arg) + largs).toTypedArray(),
                    ).use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (id != null) break
                }
                if (id == null || id in recent) continue
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
