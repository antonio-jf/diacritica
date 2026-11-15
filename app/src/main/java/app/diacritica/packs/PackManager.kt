package app.diacritica.packs

import android.content.Context
import app.diacritica.data.DictionaryDatabase
import app.diacritica.rehearse.SentenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One downloadable data pack, as listed in packs.json (built by pipeline/make_packs.py). */
@Serializable
data class PackInfo(
    val id: String,
    /** "dictionary", "sentences" (Tatoeba) or "corpora" (News, Formal, Encyclopedic). */
    val kind: String,
    val title: String,
    val description: String = "",
    val version: String,
    val url: String,
    /** Download size (compressed). */
    val bytes: Long,
    /** Size on the phone once installed. */
    val installedBytes: Long = 0,
    val sha256: String,
    val languages: List<String> = emptyList(),
    val license: String = "",
)

@Serializable
data class PackCatalog(val format: Int = 1, val packs: List<PackInfo> = emptyList())

/**
 * Downloads data packs from the project's GitHub release, checks them (SHA-256) and installs them:
 * the dictionary replaces the bundled one, sentence databases override the bundled assets.
 * Installed versions are remembered in files/packs/installed.json.
 */
class PackManager(
    private val context: Context,
    private val dictionary: DictionaryDatabase,
    private val sentences: SentenceRepository,
    private val corpora: SentenceRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val installedFile = File(dir, "installed.json")

    /** Installed pack versions by id. */
    fun installed(): Map<String, String> = runCatching {
        json.decodeFromString<Map<String, String>>(installedFile.readText())
    }.getOrDefault(emptyMap())

    private fun setInstalled(id: String, version: String?) {
        val m = installed().toMutableMap()
        if (version == null) m.remove(id) else m[id] = version
        installedFile.writeText(json.encodeToString(m))
    }

    suspend fun catalog(url: String = CATALOG_URL): PackCatalog = withContext(Dispatchers.IO) {
        val text = open(url).use { c -> c.inputStream.bufferedReader().readText() }
        json.decodeFromString(PackCatalog.serializer(), text)
    }

    /**
     * Downloads and installs [p]. [onProgress] gets (bytes so far, total). Throws with a readable message on failure;
     * nothing changes on the phone unless the whole download checks out.
     */
    suspend fun install(p: PackInfo, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(context.cacheDir, "pack-${p.id}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open(p.url).use { c ->
                val total = c.contentLengthLong.takeIf { it > 0 } ?: p.bytes
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n)
                            done += n
                            if (done - last > 256 * 1024) { onProgress(done, total); last = done }
                        }
                        onProgress(done, total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(p.sha256, ignoreCase = true)) throw IOException("The download was damaged (checksum mismatch). Try again.")

            // Unpack next to its destination (same storage, so installing is a rename).
            val target = when (p.kind) {
                "dictionary" -> File(File(context.filesDir, "dict").apply { mkdirs() }, "pack.db.tmp")
                "sentences" -> File(dir, sentences.packFile.name + ".tmp")
                "corpora" -> File(dir, corpora.packFile.name + ".tmp")
                else -> throw IOException("This version of the app doesn't know “${p.kind}” packs. Update the app.")
            }
            (if (p.url.endsWith(".gz")) GZIPInputStream(part.inputStream().buffered()) else part.inputStream().buffered()).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            part.delete()

            when (p.kind) {
                "dictionary" -> dictionary.installPack(target, p.version)?.let { throw IOException(it) }
                "sentences" -> replace(target, sentences)
                "corpora" -> replace(target, corpora)
            }
            setInstalled(p.id, p.version)
        } finally {
            part.delete()
        }
    }

    private suspend fun replace(tmp: File, repo: SentenceRepository) {
        repo.reload()
        if (!tmp.renameTo(repo.packFile)) { tmp.copyTo(repo.packFile, overwrite = true); tmp.delete() }
        repo.reload()
    }

    /** Removes a pack and goes back to what ships with the app. */
    suspend fun remove(p: PackInfo) = withContext(Dispatchers.IO) {
        when (p.kind) {
            "dictionary" -> if (dictionary.source().startsWith("pack:")) dictionary.resetToBundled()
            "sentences" -> { sentences.reload(); sentences.packFile.delete(); sentences.reload() }
            "corpora" -> { corpora.reload(); corpora.packFile.delete(); corpora.reload() }
        }
        setInstalled(p.id, null)
    }

    /** Opens [url], following redirects (GitHub release downloads redirect to a storage host). */
    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Diacritica (Android)")
            }
            when (c.responseCode) {
                in 200..299 -> return c
                301, 302, 303, 307, 308 -> { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
                404 -> { c.disconnect(); throw IOException("Not found. The data packs may not be published yet.") }
                else -> { val code = c.responseCode; c.disconnect(); throw IOException("Download failed (HTTP $code).") }
            }
        }
        throw IOException("Too many redirects.")
    }

    companion object {
        /** The project's rolling data-pack release; see pipeline/make_packs.py and README › Data packs. */
        const val CATALOG_URL = "https://github.com/antonio-jf/diacritica/releases/download/data-packs/packs.json"
    }
}
