package app.diacritica.data.online

import app.diacritica.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Downloads one word's Wiktionary data from kaikki.org (the same Wiktextract format the pipeline
 * uses), e.g. https://kaikki.org/dictionary/English/meaning/t/te/teacher.jsonl
 *
 * Requests are serialized and spaced out, to be gentle with a free service.
 */
class KaikkiClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var lastRequest = 0L

    sealed interface Result {
        data class Found(val lines: List<JsonObject>, val raw: String) : Result
        data object NotFound : Result
        data class Failed(val reason: String) : Result
    }

    private fun languageName(lang: Lang): String = when (lang) {
        Lang.EN -> "English"; Lang.FR -> "French"; Lang.DE -> "German"
        Lang.IT -> "Italian"; Lang.ES -> "Spanish"; Lang.ZH -> "Chinese"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun urlFor(lang: Lang, word: String): String {
        val c1 = word.take(1)
        val c2 = word.take(2)
        return "https://kaikki.org/dictionary/${languageName(lang)}/meaning/${enc(c1)}/${enc(c2)}/${enc(word)}.jsonl"
    }

    /** Tries the word as typed, then lowercase, then capitalized (German nouns). */
    suspend fun fetch(lang: Lang, word: String): Result {
        val variants = linkedSetOf(word.trim(), word.trim().lowercase(), word.trim().lowercase().replaceFirstChar { it.titlecase() })
        var last: Result = Result.NotFound
        for (v in variants) {
            if (v.isEmpty()) continue
            last = fetchExact(lang, v)
            if (last !is Result.NotFound) return last
        }
        return last
    }

    private suspend fun fetchExact(lang: Lang, word: String): Result = lock.withLock {
        val wait = 400 - (System.currentTimeMillis() - lastRequest)
        if (wait > 0) delay(wait)
        lastRequest = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val conn = (URL(urlFor(lang, word)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 15000
                setRequestProperty("User-Agent", "Diacritica/0.2 (personal multilingual dictionary app)")
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString()
                        } else bytes.decodeToString()
                        val lines = text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
                            .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.toList()
                        if (lines.isEmpty()) Result.NotFound else Result.Found(lines, text)
                    }
                    404, 410 -> Result.NotFound
                    else -> Result.Failed("Server answered $code")
                }
            } catch (e: FileNotFoundException) {
                Result.NotFound
            } catch (e: Exception) {
                Result.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                conn.disconnect()
            }
        }
    }
}
