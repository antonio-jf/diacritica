package app.diacritica.vocab

import android.content.Context
import app.diacritica.data.Lang
import java.text.Normalizer

/** One word of a set: the same thing in every language. Alternatives are separated by " / ". */
data class VocabWord(val forms: Map<Lang, String>, val pinyin: String) {
    fun form(l: Lang): String = forms[l].orEmpty()
}

data class VocabSet(val id: String, val title: String, val category: String, val words: List<VocabWord>)

/**
 * Topic word sets (fruits, colours, professions…), bundled as assets/vocab/sets.tsv: a "@ id | Title | Category" line,
 * then one word per line, tab-separated: English, French, German, Italian, Spanish, Mandarin, pinyin.
 * Nouns carry their article; elided ones (l') carry (m) or (f).
 */
object VocabSets {
    private val COLUMNS = listOf(Lang.EN, Lang.FR, Lang.DE, Lang.IT, Lang.ES, Lang.ZH)

    fun parse(text: String): List<VocabSet> {
        val sets = ArrayList<VocabSet>()
        var head: List<String>? = null
        val words = ArrayList<VocabWord>()
        fun flush() { head?.let { h -> sets += VocabSet(h[0], h[1], h[2], words.toList()) }; words.clear() }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (line.startsWith("@")) {
                flush()
                head = line.removePrefix("@").split("|").map { it.trim() }.takeIf { it.size >= 3 }
                continue
            }
            val cols = line.split('\t')
            if (cols.size < 7 || head == null) continue
            words += VocabWord(COLUMNS.indices.associate { COLUMNS[it] to cols[it].trim() }, cols[6].trim())
        }
        flush()
        return sets
    }

    @Volatile private var cache: List<VocabSet>? = null

    fun all(context: Context): List<VocabSet> = cache ?: context.assets.open("vocab/sets.tsv").bufferedReader().use { parse(it.readText()) }.also { cache = it }
}

/** Checking typed answers, and the plain dictionary form of an entry (for saving to Review). */
object VocabCheck {
    enum class Grade { RIGHT, ALMOST, WRONG }
    data class Result(val grade: Grade, val note: String? = null)

    private val ARTICLES = mapOf(
        Lang.FR to listOf("le", "la", "les", "l'", "l’", "un", "une"),
        Lang.DE to listOf("der", "die", "das", "den", "dem", "des", "ein", "eine"),
        Lang.IT to listOf("il", "lo", "la", "i", "gli", "le", "l'", "l’", "un", "uno", "una"),
        Lang.ES to listOf("el", "la", "los", "las", "un", "una"),
        Lang.EN to listOf("the", "a", "an", "to"),
    )

    /** "l'orange (f)" → "orange"; "der Apfel" → "Apfel"; "to eat" → "eat". */
    fun bare(s: String, lang: Lang): String {
        var t = s.trim().replace(Regex("\\s*\\((m|f|m/f)\\)$"), "").trim()
        for (a in ARTICLES[lang].orEmpty().sortedByDescending { it.length }) {
            if (a.endsWith("'") || a.endsWith("’")) {
                if (t.lowercase().startsWith(a)) { t = t.substring(a.length).trim(); break }
            } else if (t.lowercase().startsWith("$a ")) { t = t.substring(a.length + 1).trim(); break }
        }
        return t
    }

    /** The article of an entry, normalized ("l'" for elided), or null. */
    fun article(s: String, lang: Lang): String? {
        val t = s.trim().lowercase()
        return ARTICLES[lang].orEmpty().sortedByDescending { it.length }.firstOrNull { a ->
            if (a.endsWith("'") || a.endsWith("’")) t.startsWith(a) else t.startsWith("$a ")
        }?.replace('’', '\'')
    }

    fun alternatives(s: String): List<String> = s.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }

    /** First form, without its article: what gets saved and looked up. */
    fun headword(s: String, lang: Lang): String = bare(alternatives(s).firstOrNull() ?: s, lang)

    private fun norm(s: String) = s.lowercase().replace('’', '\'').replace(Regex("[\\s.,!?;:¡¿]+"), " ").trim()
    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("ß", "ss")
    private fun pinyinKey(s: String) = fold(s.lowercase()).replace(Regex("[^a-zü]"), "").replace("ü", "v")

    fun check(answer: String, w: VocabWord, lang: Lang): Result {
        val a = norm(answer)
        if (a.isEmpty()) return Result(Grade.WRONG)
        if (lang == Lang.ZH) {
            val chars = alternatives(w.form(Lang.ZH))
            val pinyin = alternatives(w.pinyin)
            if (chars.any { a.replace(" ", "") == it.replace(" ", "").lowercase() }) return Result(Grade.RIGHT)
            if (pinyin.any { norm(it).replace(" ", "").replace("'", "") == a.replace(" ", "").replace("'", "") }) return Result(Grade.RIGHT)
            if (pinyin.any { pinyinKey(it) == pinyinKey(a) }) return Result(Grade.ALMOST, "Check the tones")
            return Result(Grade.WRONG)
        }
        var best = Result(Grade.WRONG)
        for (alt in alternatives(w.form(lang))) {
            val full = norm(alt.replace(Regex("\\s*\\((m|f|m/f)\\)$"), ""))
            val noun = norm(bare(alt, lang))
            val typedNoun = norm(bare(answer, lang))
            val want = article(alt, lang)
            val typed = article(answer, lang)
            if (a == full || (a == noun && want == null)) return Result(Grade.RIGHT)
            if (typedNoun == noun) {
                // Right word: the article decides between right and "almost".
                val r = when {
                    want == null || typed == null -> Result(Grade.RIGHT, if (want != null && lang != Lang.EN) "With its article: $alt" else null)
                    want == "l'" || typed == want -> Result(Grade.RIGHT)
                    else -> Result(Grade.ALMOST, "Article: $alt")
                }
                if (r.grade == Grade.RIGHT) return r
                best = r
            } else if (fold(typedNoun) == fold(noun) && best.grade == Grade.WRONG) {
                best = Result(Grade.ALMOST, "Accents: $alt")
            }
        }
        return best
    }
}
