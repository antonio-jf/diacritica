package app.diacritica.update

import android.app.DownloadManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import app.diacritica.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** An update patch on a release: turns the APK of version [from] into this release's APK. */
data class PatchAsset(val from: Int, val url: String, val bytes: Long)

/** A published app release: tag v<line>.<version> (v1.0.N, earlier v0.3.N), where version is the build's versionCode. */
data class AppRelease(
    val version: Int, val tag: String, val apkUrl: String, val bytes: Long, val shaUrl: String?, val notesUrl: String,
    val patches: List<PatchAsset> = emptyList(),
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long, val total: Long, val patch: Boolean = false) : UpdateState
    /** The patch is downloaded; rebuilding the new APK from the installed one. */
    data class Preparing(val release: AppRelease) : UpdateState
    /** Handed to Android's installer; the app closes while it updates. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Checks GitHub Releases for a newer APK, downloads it with Android's DownloadManager (retries and
 * resumes on flaky connections, shows progress in the notification shade), verifies its SHA-256
 * against the release's .sha256 file, then installs it through PackageInstaller.
 *
 * Patch updates: when the release has a patch from the installed version (pipeline/make_patch.py),
 * only the patch is downloaded and the new APK is rebuilt from the installed one ([Bspatch]). If the
 * installed APK isn't the release file (a build from Android Studio) or anything fails, it falls
 * back to the full APK by itself.
 *
 * No prompt: from Android 12, once Diacritica has installed itself once, later updates install
 * without Android's "Do you want to install this update?" screen (USER_ACTION_NOT_REQUIRED).
 */
class Updater(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var poll: Job? = null

    val currentVersion: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME
    private val apkFile: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "diacritica-update.apk")
    private val patchFile: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "diacritica-update.patch")
    private val installedApk: File? get() = context.applicationInfo.takeIf { it.splitSourceDirs.isNullOrEmpty() }?.sourceDir?.let(::File)

    /** The patch from the installed version, unless it already failed for this release. */
    fun patchFor(r: AppRelease): PatchAsset? =
        r.patches.firstOrNull { it.from == currentVersion }
            ?.takeIf { installedApk?.canRead() == true && prefs.getInt("patch_failed", 0) != r.version }

    /** What [download] will fetch: the patch when there is one, else the whole APK. */
    fun downloadSize(r: AppRelease): Long = patchFor(r)?.bytes ?: r.bytes

    /** Android 12+ and Diacritica installed the current version itself: the next install needs no confirmation. */
    val installsWithoutPrompt: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == context.packageName
    }.getOrDefault(false)

    /** Check by itself at most every 20 hours (Settings › App updates). */
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(v) { prefs.edit().putBoolean("auto_check", v).apply() }

    /** Checks at most every 20 hours unless [force]; resumes a download still in progress from last time. */
    fun check(force: Boolean = false) {
        if (!force && !autoCheck) { resumeIfDownloading(); return }
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val last = prefs.getLong("last_check", 0)
        if (!force && System.currentTimeMillis() - last < 20 * 3600_000L) { resumeIfDownloading(); return }
        _state.value = UpdateState.Checking
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { latest() } }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            result.onSuccess { r ->
                _state.value = when {
                    r == null || r.version <= currentVersion -> UpdateState.UpToDate
                    apkFile.exists() && prefs.getInt("ready_version", 0) == r.version -> UpdateState.Ready(r, apkFile)
                    else -> UpdateState.Available(r)
                }
                resumeIfDownloading()
            }.onFailure { _state.value = if (force) UpdateState.Failed("Couldn't reach GitHub. Check your connection.") else UpdateState.Idle }
        }
    }

    /** The newest app release (tags vX.Y.N with an .apk; N decides), skipping data-pack releases. */
    private fun latest(): AppRelease? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=15").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Diacritica/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val arr = json.parseToJsonElement(conn.inputStream.use { it.readBytes().decodeToString() }) as JsonArray
            return parseReleases(arr).maxByOrNull { it.version }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the patch for the installed version if there is one, else the whole APK. */
    fun download(r: AppRelease) {
        val patch = patchFor(r)
        if (patch != null) enqueue(r, patch.url, patchFile, patch = true, size = patch.bytes)
        else enqueue(r, r.apkUrl, apkFile, patch = false, size = r.bytes)
    }

    private fun enqueue(r: AppRelease, url: String, file: File, patch: Boolean, size: Long) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        apkFile.delete(); patchFile.delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Diacritica ${r.tag.removePrefix("v")}")
            .setDescription(if (patch) "App update (only the changes)" else "App update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
        val id = dm.enqueue(req)
        prefs.edit().putLong("download_id", id).putString("download_release", encode(r)).putBoolean("download_patch", patch)
            .remove("ready_version").apply()
        _state.value = UpdateState.Downloading(r, 0, size, patch)
        watch(id, r)
    }

    private fun resumeIfDownloading() {
        val id = prefs.getLong("download_id", -1)
        val r = prefs.getString("download_release", null)?.let(::decode) ?: return
        if (id >= 0 && poll == null) watch(id, r)
    }

    private fun watch(id: Long, r: AppRelease) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        poll?.cancel()
        poll = scope.launch {
            while (true) {
                val row = withContext(Dispatchers.IO) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) null else Triple(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        )
                    }
                }
                val patch = prefs.getBoolean("download_patch", false)
                if (row == null) { clearDownload(); _state.value = UpdateState.Available(r); break }
                val (status, done, total) = row
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { if (patch) rebuild(r) else verify(r); break }
                    DownloadManager.STATUS_FAILED -> {
                        clearDownload()
                        if (patch) fullInstead(r)
                        else _state.value = UpdateState.Failed("The download failed. Try again.", r)
                        break
                    }
                    // Pending, running or paused (waiting for network): DownloadManager resumes by itself.
                    else -> _state.value = UpdateState.Downloading(r, done, if (total > 0) total else downloadSize(r), patch)
                }
                delay(1000)
            }
            poll = null
        }
    }

    /** Patch downloaded: rebuild the new APK from the installed one, then check it like a full download. */
    private suspend fun rebuild(r: AppRelease) {
        clearDownload()
        _state.value = UpdateState.Preparing(r)
        val base = installedApk
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                Bspatch.apply(base ?: error("no installed APK"), patchFile, apkFile)
            }.onFailure { android.util.Log.w("Updater", "Patch didn't apply, downloading the full APK", it) }.isSuccess
        }
        patchFile.delete()
        if (ok) verify(r) else fullInstead(r)
    }

    /** The patch couldn't be used (other base, damaged): get the whole APK for this release instead. */
    private fun fullInstead(r: AppRelease) {
        prefs.edit().putInt("patch_failed", r.version).apply()
        enqueue(r, r.apkUrl, apkFile, patch = false, size = r.bytes)
    }

    private suspend fun verify(r: AppRelease) {
        clearDownload()
        val ok = withContext(Dispatchers.IO) {
            if (!apkFile.exists() || (r.bytes > 0 && apkFile.length() != r.bytes)) return@withContext false
            val expected = r.shaUrl?.let { runCatching { fetchText(it).trim().substringBefore(' ').lowercase() }.getOrNull() }
                ?: return@withContext true
            sha256(apkFile) == expected
        }
        if (ok) {
            prefs.edit().putInt("ready_version", r.version).apply()
            _state.value = UpdateState.Ready(r, apkFile)
        } else {
            apkFile.delete()
            _state.value = UpdateState.Failed("The download was damaged (checksum mismatch). Try again.", r)
        }
    }

    /**
     * Installs through PackageInstaller. The first time, Android asks to allow installs from Diacritica:
     * that settings screen opens instead, then tap Install again. On Android 12+, once Diacritica has
     * installed itself, there's no confirmation screen; the app closes, updates, and a notification
     * offers to open it again ([UpdateReceiver]).
     */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
            return
        }
        val r = (_state.value as? UpdateState.Ready)?.release
        val ok = runCatching { installWithSession(file) }
            .onFailure { android.util.Log.w("Updater", "PackageInstaller session failed, using the installer screen", it) }
            .isSuccess
        if (ok) {
            prefs.edit().putInt("installing_version", r?.version ?: 0).apply()
            if (r != null) _state.value = UpdateState.Installing(r)
        } else installWithIntent(file)
    }

    private fun installWithSession(file: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_RESULT), flags,
            )
            session.commit(result.intentSender)
        }
    }

    /** Called by [UpdateReceiver] when Android reports that the install didn't happen. */
    fun installEnded(message: String?) {
        prefs.edit().remove("installing_version").apply()
        val r = (_state.value as? UpdateState.Installing)?.release ?: return
        _state.value = if (message == null) UpdateState.Ready(r, apkFile) else UpdateState.Failed(message, r)
    }

    /** The version this app just asked Android to install (for the "updated" notification), then forgets it. */
    fun takeInstalledVersion(): Int = prefs.getInt("installing_version", 0).also { prefs.edit().remove("installing_version").apply() }

    private fun installWithIntent(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    private fun clearDownload() = prefs.edit().remove("download_id").remove("download_release").apply()

    private fun fetchText(url: String): String {
        var u = url
        repeat(5) {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 20_000
                setRequestProperty("User-Agent", "Diacritica/${BuildConfig.VERSION_NAME}")
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> u = conn.getHeaderField("Location") ?: error("redirect without location")
                    200 -> return conn.inputStream.use { it.readBytes().decodeToString() }
                    else -> error("HTTP ${conn.responseCode}")
                }
            } finally { conn.disconnect() }
        }
        error("too many redirects")
    }

    private fun encode(r: AppRelease) = listOf(
        r.version, r.tag, r.apkUrl, r.bytes, r.shaUrl.orEmpty(), r.notesUrl,
        r.patches.joinToString(" ") { "${it.from},${it.bytes},${it.url}" },
    ).joinToString("\t")
    private fun decode(s: String): AppRelease? = runCatching {
        val p = s.split('\t')
        val patches = p.getOrNull(6).orEmpty().split(' ').filter { it.isNotBlank() }.map {
            val (from, bytes, url) = it.split(',', limit = 3)
            PatchAsset(from.toInt(), url, bytes.toLong())
        }
        AppRelease(p[0].toInt(), p[1], p[2], p[3].toLong(), p[4].ifEmpty { null }, p[5], patches)
    }.getOrNull()

    companion object {
        const val REPO = "antonio-jf/diacritica"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Version number from a tag: "v1.0.107" → 107 ("v0.3.27" → 27). Null for other tags (data-packs…). */
        fun versionOf(tag: String): Int? = Regex("^v\\d+\\.\\d+\\.(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()

        fun parseReleases(arr: JsonArray): List<AppRelease> = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o["draft"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
            val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val version = versionOf(tag) ?: return@mapNotNull null
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun name(a: JsonObject) = (a["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun url(a: JsonObject) = (a["browser_download_url"] as? JsonPrimitive)?.contentOrNull
            val apk = assets.firstOrNull { name(it).endsWith(".apk") } ?: return@mapNotNull null
            AppRelease(
                version = version, tag = tag, apkUrl = url(apk) ?: return@mapNotNull null,
                bytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0,
                shaUrl = assets.firstOrNull { name(it).endsWith(".apk.sha256") }?.let(::url),
                patches = assets.mapNotNull { a ->
                    val from = Regex("-from-(\\d+)\\.patch$").find(name(a))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    PatchAsset(from, url(a) ?: return@mapNotNull null, (a["size"] as? JsonPrimitive)?.longOrNull ?: 0)
                },
                notesUrl = (o["html_url"] as? JsonPrimitive)?.contentOrNull ?: "https://github.com/$REPO/releases",
            )
        }
    }
}
