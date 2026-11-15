package app.diacritica.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.diacritica.data.user.UserDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup & Restore: one .zip with everything that is yours.
 *  - user.db: history, saved words, Review cards and logs, Rehearse archive, stories
 *  - settings.preferences_pb: every setting, including the Gemini key
 *  - fetched.jsonl: words downloaded from Wiktionary (re-applied to the dictionary on restore)
 *  - tests.json: Tests attempts, reviews and lessons
 * The dictionary itself is not included: it ships with the app.
 *
 * File names inside the app (user.db, settings, dict/fetched.jsonl) never change between versions,
 * so a backup made by a build with the app's earlier name and ID restores here unchanged.
 * Room migrates an older user.db on first open.
 */
class BackupManager(private val context: Context, private val userDb: UserDatabase) {

    private val dbFile get() = context.getDatabasePath(DB_NAME)
    private val settingsFile get() = File(context.filesDir, "datastore/settings.preferences_pb")
    private val dictDir get() = File(context.filesDir, "dict")
    private val fetchedFile get() = File(dictDir, "fetched.jsonl")
    private val testsFile get() = File(context.filesDir, "tests/attempts.json")

    /** Writes the backup to [uri]. Returns a short summary for the user. */
    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into user.db so the single file is complete.
        userDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val out = context.contentResolver.openOutputStream(uri) ?: error("Could not open the file for writing.")
        var bytes = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = buildJsonObject {
                put("app", "diacritica")
                put("format", FORMAT)
                put("createdAt", System.currentTimeMillis())
                put("dbVersion", userDb.openHelper.readableDatabase.version)
            }
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            for ((name, file) in listOf(DB_ENTRY to dbFile, SETTINGS_ENTRY to settingsFile, FETCHED_ENTRY to fetchedFile, TESTS_ENTRY to testsFile)) {
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { bytes += it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        "Backup saved (${bytes / 1024} KB). It includes your Gemini key, so keep it private."
    }

    /**
     * Replaces this app's data with the backup at [uri], then restarts the app.
     * Returns an error message if the file isn't a valid backup (nothing is changed then).
     */
    suspend fun restore(uri: Uri): String? = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext "Could not open the file."
        var manifest: JsonObject? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    MANIFEST -> manifest = runCatching { Json.parseToJsonElement(zip.readBytes().decodeToString()) as JsonObject }.getOrNull()
                    DB_ENTRY, SETTINGS_ENTRY, FETCHED_ENTRY, TESTS_ENTRY -> File(tmp, e.name).outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val m = manifest
        if (m == null || !File(tmp, DB_ENTRY).exists()) return@withContext "This file isn't a Diacritica backup."
        val format = runCatching { m["format"]!!.jsonPrimitive.int }.getOrDefault(0)
        val backupDb = runCatching { m["dbVersion"]!!.jsonPrimitive.int }.getOrDefault(0)
        if (format > FORMAT || backupDb > userDb.openHelper.readableDatabase.version) {
            return@withContext "This backup was made by a newer version. Update the app first."
        }

        userDb.close()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
        File(tmp, DB_ENTRY).copyTo(dbFile, overwrite = true)
        File(tmp, SETTINGS_ENTRY).takeIf { it.exists() }?.let { settingsFile.parentFile?.mkdirs(); it.copyTo(settingsFile, overwrite = true) }
        File(tmp, FETCHED_ENTRY).takeIf { it.exists() }?.let {
            dictDir.mkdirs()
            it.copyTo(fetchedFile, overwrite = true)
            // Forget which dictionary file is installed, so the next start re-installs it and replays the downloads.
            File(dictDir, "source.txt").delete()
        }
        File(tmp, TESTS_ENTRY).takeIf { it.exists() }?.let { testsFile.parentFile?.mkdirs(); it.copyTo(testsFile, overwrite = true) }
        tmp.deleteRecursively()
        restart()
        null
    }

    /** Settings and databases are held open in memory, so the cleanest way to load the restored files is a fresh start. */
    private fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val DB_NAME = "user.db"
        private const val FORMAT = 1
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "user.db"
        private const val SETTINGS_ENTRY = "settings.preferences_pb"
        private const val FETCHED_ENTRY = "fetched.jsonl"
        private const val TESTS_ENTRY = "tests.json"
    }
}
