package app.diacritica.data.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.diacritica.data.DictionaryDatabase
import app.diacritica.data.Lang
import app.diacritica.data.LangResult
import app.diacritica.data.TextNorm

/**
 * Fetch-and-save for words that are not in the offline dictionary yet.
 *
 * English entries carry the translation tables, so a foreign word is fetched first and then the
 * English words its glosses point to (enseignant → "teacher"), which links it to every language.
 */
class OnlineLookup(
    private val context: Context,
    private val database: DictionaryDatabase,
    private val client: KaikkiClient = KaikkiClient(),
) {
    enum class Outcome { FOUND, NOT_FOUND, OFFLINE, FAILED }

    /** Words already tried for enrichment in this session, so a lookup never re-downloads in a loop. */
    private val tried = HashSet<String>()

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Downloads [query] in [source] (or in each language when auto-detecting) and stores it. */
    suspend fun fetchWord(query: String, source: Lang?, active: List<Lang>): Pair<Outcome, Lang?> {
        val q = query.trim()
        // Mandarin is fully covered offline by CC-CEDICT.
        if (q.isEmpty() || TextNorm.containsHan(q) || source == Lang.ZH) return Outcome.NOT_FOUND to null
        if (!isOnline()) return Outcome.OFFLINE to null
        val candidates = (source?.let { listOf(it) } ?: (listOf(Lang.EN) + active)).filter { it != Lang.ZH }.distinct()
        var failed = false
        for (lang in candidates) {
            when (val r = client.fetch(lang, q)) {
                is KaikkiClient.Result.Found -> {
                    database.storeFetched(lang, q, r.lines, r.raw)
                    if (lang != Lang.EN) {
                        for (en in WiktextractImporter.englishCandidates(r.lines).take(2)) {
                            val er = client.fetch(Lang.EN, en)
                            if (er is KaikkiClient.Result.Found) database.storeFetched(Lang.EN, en, er.lines, er.raw)
                        }
                    }
                    return Outcome.FOUND to lang
                }
                is KaikkiClient.Result.Failed -> failed = true
                KaikkiClient.Result.NotFound -> {}
            }
        }
        return (if (failed) Outcome.FAILED else Outcome.NOT_FOUND) to null
    }

    /**
     * Fills in gender, plural, IPA and examples for translations that have no entry of their own yet.
     * Returns true when something new was stored.
     */
    suspend fun enrich(results: List<LangResult>, max: Int = 5): Boolean {
        if (!isOnline()) return false
        var stored = false
        results.flatMap { it.words.take(1) }
            .filter { it.lang != Lang.ZH && it.lang != Lang.EN && it.ipa == null && it.example == null && it.plural == null }
            .filter { tried.add(it.lang.code + ":" + it.word) }
            .take(max)
            .forEach { w ->
                val r = client.fetch(w.lang, w.word)
                if (r is KaikkiClient.Result.Found) {
                    database.storeFetched(w.lang, w.word, r.lines, r.raw); stored = true
                }
            }
        return stored
    }
}
