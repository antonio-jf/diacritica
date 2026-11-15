package app.diacritica.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.diacritica.data.online.WiktextractImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The prebuilt dictionary (built by pipeline/build_dictionary.py), plus words downloaded later.
 *
 * On first launch it is copied from assets/dict/dictionary.db into app storage. A dictionary
 * imported by the user replaces it and is never overwritten by the bundled one.
 *
 * Words fetched online are inserted into the same file AND appended to fetched.jsonl, so they are
 * re-applied whenever the dictionary file is replaced (new bundled version, import, reset).
 */
class DictionaryDatabase(private val context: Context) {
    private val dir = File(context.filesDir, "dict").apply { mkdirs() }
    private val dbFile = File(dir, "dictionary.db")
    private val sourceFile = File(dir, "source.txt")
    private val fetchedLog = File(dir, "fetched.jsonl")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var db: SQLiteDatabase? = null

    private val _version = MutableStateFlow(0)
    /** Increments whenever the dictionary file is replaced, so screens can refresh. */
    val version: StateFlow<Int> = _version

    suspend fun get(): SQLiteDatabase = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            installBundledIfNeeded()
            open().also { repair(it); db = it }
        }
    }

    private fun open(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun bundledSize(): Long =
        context.assets.openFd(ASSET).use { it.length }

    private fun installBundledIfNeeded() {
        val source = if (sourceFile.exists()) sourceFile.readText() else ""
        if (source == "import" && dbFile.exists()) return
        val marker = "asset:${bundledSize()}"
        if (dbFile.exists() && source == marker) return
        val tmp = File(dir, "dictionary.db.tmp")
        context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dbFile)) {
            tmp.copyTo(dbFile, overwrite = true); tmp.delete()
        }
        sourceFile.writeText(marker)
        replayFetched()
    }

    /** Stores a downloaded word: inserts it now and logs it for future re-application. */
    suspend fun storeFetched(lang: Lang, word: String, lines: List<JsonObject>, raw: String) {
        val d = get()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                d.beginTransaction()
                try {
                    WiktextractImporter.deleteWord(d, lang, word)
                    lines.mapNotNull { (it["word"] as? JsonPrimitive)?.content }.distinct()
                        .filter { it != word }.forEach { WiktextractImporter.deleteWord(d, lang, it) }
                    WiktextractImporter.importWord(d, lang, lines)
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
                val rec = buildJsonObject { put("lang", lang.code); put("word", word); put("raw", raw) }
                fetchedLog.appendText(rec.toString() + "\n")
            }
        }
    }

    /** Re-applies every downloaded word to a freshly installed dictionary file. */
    private fun replayFetched() {
        if (!fetchedLog.exists()) return
        val d = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            d.beginTransaction()
            fetchedLog.forEachLine { line ->
                runCatching {
                    val rec = json.parseToJsonElement(line) as JsonObject
                    val lang = Lang.fromCode(rec["lang"]!!.jsonPrimitive.content) ?: return@runCatching
                    val word = rec["word"]!!.jsonPrimitive.content
                    val objs = rec["raw"]!!.jsonPrimitive.content.lineSequence().filter { it.trim().startsWith("{") }
                        .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.toList()
                    WiktextractImporter.deleteWord(d, lang, word)
                    WiktextractImporter.importWord(d, lang, objs)
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction(); d.close()
        }
    }

    /**
     * One-time fixes for dictionaries built by older pipeline versions:
     * Mandarin translations stored as "老師 /老师" are split into the simplified headword.
     */
    private fun repair(d: SQLiteDatabase) {
        val done = d.rawQuery("SELECT value FROM meta WHERE key = 'zh_clean'", null).use { it.moveToFirst() }
        if (done) return
        d.beginTransaction()
        try {
            val rows = d.rawQuery(
                "SELECT rowid, word FROM translation WHERE lang = 'zh' AND (word LIKE '%/%' OR word LIKE '%(%' OR word LIKE '%（%' OR word LIKE '-%' OR word LIKE '%-')",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
            for ((rowid, word) in rows) {
                val split = ZhClean.split(word)
                if (split == null) {
                    d.execSQL("DELETE FROM translation WHERE rowid = ?", arrayOf(rowid))
                    continue
                }
                val simp = d.rawQuery("SELECT simp, pinyin FROM zh WHERE simp = ?1 OR trad = ?1 ORDER BY (simp = ?1) DESC LIMIT 1", arrayOf(split.simplified))
                    .use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
                val w = simp?.first ?: split.simplified
                d.execSQL(
                    "UPDATE translation SET word = ?, norm = ?, roman = COALESCE(roman, ?) WHERE rowid = ?",
                    arrayOf(w, TextNorm.normalize(w), simp?.second, rowid),
                )
            }
            d.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES ('zh_clean', '1')")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Number of words downloaded so far. */
    fun fetchedCount(): Int = if (fetchedLog.exists()) fetchedLog.useLines { it.count() } else 0

    /** Validates and installs a dictionary file chosen by the user. Returns an error message or null. */
    suspend fun import(uri: Uri): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(dir, "import.db.tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: return@withContext "Could not open the file."
                val error = validate(tmp)
                if (error != null) return@withContext error
                db?.close(); db = null
                if (!tmp.renameTo(dbFile)) { tmp.copyTo(dbFile, overwrite = true) }
                sourceFile.writeText("import")
                replayFetched()
                db = open().also { repair(it) }
                _version.value += 1
                null
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            } finally {
                tmp.delete()
            }
        }
    }

    /** Goes back to the dictionary bundled in the app. */
    suspend fun resetToBundled() = mutex.withLock {
        withContext(Dispatchers.IO) {
            db?.close(); db = null
            sourceFile.delete()
            installBundledIfNeeded()
            db = open().also { repair(it) }
            _version.value += 1
        }
    }

    private fun validate(file: File): String? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            val v = d.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            when {
                v == null -> "This file is not a Diacritica dictionary (no schema version)."
                v.toIntOrNull() != SCHEMA_VERSION -> "Dictionary schema $v is not supported (expected $SCHEMA_VERSION)."
                else -> null
            }
        }
    } catch (e: Exception) {
        "This file is not a valid SQLite dictionary."
    }

    suspend fun info(): DictionaryInfo {
        val d = get()
        return withContext(Dispatchers.IO) {
            val meta = HashMap<String, String>()
            d.rawQuery("SELECT key, value FROM meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            DictionaryInfo(
                label = meta["label"] ?: "Dictionary",
                builtAt = meta["built_at"] ?: "?",
                languages = (meta["languages"] ?: "").split(',').mapNotNull { Lang.fromCode(it) },
                sizeBytes = dbFile.length(),
            )
        }
    }

    companion object {
        const val ASSET = "dict/dictionary.db"
        const val SCHEMA_VERSION = 1
    }
}
