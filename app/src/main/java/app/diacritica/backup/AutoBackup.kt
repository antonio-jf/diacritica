package app.diacritica.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.diacritica.DiacriticaApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Automatic backup's settings and last result. */
data class AutoBackupStatus(val folder: Uri?, val folderName: String?, val lastAt: Long?, val lastResult: String?)

/**
 * Daily backup into a folder you choose (Google Drive, a USB stick, the phone's Documents…): one
 * dated file per day, keeping the newest [KEEP]. Restore on another device with Settings › Restore.
 * Runs with WorkManager, so it happens even when the app isn't open.
 */
class AutoBackup(private val context: Context) {
    private val prefs = context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<AutoBackupStatus> = _status.asStateFlow()

    private fun read() = AutoBackupStatus(
        prefs.getString("folder", null)?.let(Uri::parse),
        prefs.getString("folder_name", null),
        prefs.getLong("last_at", 0).takeIf { it > 0 },
        prefs.getString("last_result", null),
    )

    /** Remembers the chosen folder (keeping access across restarts) and schedules the daily backup. */
    fun enable(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val name = DocumentFile.fromTreeUri(context, tree)?.name
        prefs.edit().putString("folder", tree.toString()).putString("folder_name", name).apply()
        _status.value = read()
        schedule()
        runNow()
    }

    fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        prefs.getString("folder", null)?.let { u ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs.edit().remove("folder").remove("folder_name").apply()
        _status.value = read()
    }

    fun schedule() {
        if (prefs.getString("folder", null) == null) return
        val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow() {
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().build())
    }

    /** Writes today's backup into the folder and deletes the oldest beyond [KEEP]. Returns a summary. */
    suspend fun backUp(manager: BackupManager): String {
        val tree = prefs.getString("folder", null)?.let(Uri::parse) ?: return "No backup folder chosen."
        val dir = DocumentFile.fromTreeUri(context, tree)?.takeIf { it.canWrite() }
            ?: return record("Can't write to the backup folder any more. Choose it again in Settings.")
        val name = "diacritica-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip"
        dir.findFile(name)?.delete()
        val file = dir.createFile("application/zip", name) ?: return record("Couldn't create the backup file.")
        val summary = runCatching { manager.export(file.uri) }.getOrElse { return record("Backup failed: ${it.message ?: "unknown error"}") }
        dir.listFiles().filter { it.name?.startsWith("diacritica-backup-") == true && it.name?.endsWith(".zip") == true }
            .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        return record(summary.substringBefore(". It includes") + ".")
    }

    private fun record(result: String): String {
        prefs.edit().putLong("last_at", System.currentTimeMillis()).putString("last_result", result).apply()
        _status.value = read()
        return result
    }

    companion object {
        const val KEEP = 3
        private const val WORK = "auto-backup"
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DiacriticaApp).container
        val result = c.autoBackup.backUp(c.backup)
        return when {
            result.startsWith("Backup saved") -> Result.success()
            // Folder gone or not chosen: retrying won't help; Settings shows the message.
            result.startsWith("Can't write") || result.startsWith("No backup folder") -> Result.failure()
            else -> Result.retry()
        }
    }
}
