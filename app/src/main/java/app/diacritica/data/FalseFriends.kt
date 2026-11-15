package app.diacritica.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One pair of look-alike words that mean different things, e.g. ES embarazada / EN embarrassed. */
@Serializable
data class FalseFriendPair(
    val a: String, val aw: String, val am: String, val ab: String,
    val b: String, val bw: String, val bm: String, val ba: String,
)

@Serializable
private data class FalseFriendFile(val format: Int = 1, val pairs: List<FalseFriendPair>)

/**
 * A warning seen from one word's side: [word] in [lang] means [means]; it is NOT [otherWord] in
 * [otherLang] (which means [otherMeans]). [sayInstead] is how to say [otherWord]'s meaning in [lang],
 * and [rightWord] is how to say [word]'s meaning in [otherLang].
 */
data class FalseFriendWarning(
    val lang: Lang,
    val word: String,
    val means: String,
    val otherLang: Lang,
    val otherWord: String,
    val otherMeans: String,
    val sayInstead: String,
    val rightWord: String,
) {
    val text: String
        get() = "$word ≠ $otherWord (${otherLang.code.uppercase()}). $word = $means; " +
            "“$otherWord” means $otherMeans — in ${lang.englishName}: $sayInstead."
}

/** Curated list of false friends between the app's languages, matched on normalized spelling. */
class FalseFriendIndex(pairs: List<FalseFriendPair>) {
    private val byKey: Map<String, List<FalseFriendWarning>>

    init {
        val m = HashMap<String, MutableList<FalseFriendWarning>>()
        for (p in pairs) {
            val la = Lang.fromCode(p.a) ?: continue
            val lb = Lang.fromCode(p.b) ?: continue
            m.getOrPut(key(la, p.aw)) { mutableListOf() } += FalseFriendWarning(la, p.aw, p.am, lb, p.bw, p.bm, p.ba, p.ab)
            m.getOrPut(key(lb, p.bw)) { mutableListOf() } += FalseFriendWarning(lb, p.bw, p.bm, la, p.aw, p.am, p.ab, p.ba)
        }
        byKey = m
    }

    val size: Int get() = byKey.values.sumOf { it.size } / 2

    /** Warnings for [word] in [lang] whose look-alike is in one of [languages]. */
    fun warnings(lang: Lang, word: String, languages: Collection<Lang>): List<FalseFriendWarning> =
        byKey[key(lang, stripArticle(word))].orEmpty().filter { it.otherLang in languages && it.otherLang != lang }

    /** Warnings for several words at once (a headword and the translations shown for it), deduplicated. */
    fun warningsFor(words: List<Pair<Lang, String>>, languages: Collection<Lang>, max: Int = 4): List<FalseFriendWarning> =
        words.flatMap { (l, w) -> warnings(l, w, languages) }
            .distinctBy { setOf(it.lang.code + ":" + TextNorm.normalize(it.word), it.otherLang.code + ":" + TextNorm.normalize(it.otherWord)) }
            .take(max)

    private fun key(lang: Lang, word: String) = lang.code + ":" + TextNorm.normalize(word)

    private fun stripArticle(w: String): String {
        val t = w.trim()
        val sp = t.indexOf(' ')
        return if (sp in 1..4 && t.substring(0, sp).lowercase() in articles) t.substring(sp + 1) else t.removePrefix("l'").removePrefix("l’")
    }

    companion object {
        private val articles = setOf("der", "die", "das", "le", "la", "les", "il", "lo", "gli", "i", "el", "los", "las", "un", "una", "une", "ein", "eine", "to", "a", "an", "the")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): FalseFriendIndex = FalseFriendIndex(json.decodeFromString<FalseFriendFile>(text).pairs)

        @Volatile private var loaded: FalseFriendIndex? = null

        /** The bundled list (assets/falsefriends/false_friends.json), read once. */
        fun get(context: Context): FalseFriendIndex = loaded ?: synchronized(this) {
            loaded ?: runCatching {
                parse(context.assets.open("falsefriends/false_friends.json").bufferedReader().use { it.readText() })
            }.getOrElse { FalseFriendIndex(emptyList()) }.also { loaded = it }
        }
    }
}
