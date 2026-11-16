package app.diacritica.data

import android.content.Context

/**
 * Mandarin data the dictionary file doesn't carry, bundled in assets/zh:
 *  - hsk.tsv: HSK 3.0 level per word (7 = levels 7–9)
 *  - main_readings.tsv: the everyday reading of common characters that have several (看 kàn, not kān)
 */
class ZhLexicon(
    private val hsk: Map<String, Int>,
    private val mainReading: Map<String, String>,
    private val charRank: Map<Char, Int> = emptyMap(),
) {
    fun hsk(word: String): Int? = hsk[word]
    fun mainReading(word: String): String? = mainReading[word]

    /** 1 = most common character; null when it's outside the ranked ~3,000. */
    fun charRank(ch: Char): Int? = charRank[ch]

    /** True if [word] has a Chinese character outside the [top] most common (so its pinyin is worth showing). */
    fun hasRareChar(word: String, top: Int): Boolean =
        word.any { TextNorm.containsHan(it.toString()) && (charRank[it] ?: Int.MAX_VALUE) > top }

    companion object {
        val EMPTY = ZhLexicon(emptyMap(), emptyMap())

        fun parseRanks(text: String): Map<Char, Int> =
            text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.joinToString("").trim()
                .withIndex().associate { (i, ch) -> ch to i + 1 }

        fun parse(hskTsv: String, readingsTsv: String, charRanks: String = ""): ZhLexicon {
            fun pairs(t: String) = t.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0].trim() to it[1].trim() } }
            return ZhLexicon(
                pairs(hskTsv).mapNotNull { (w, l) -> l.toIntOrNull()?.let { w to it } }.toMap(),
                pairs(readingsTsv).toMap(),
                parseRanks(charRanks),
            )
        }

        fun fromAssets(context: Context): ZhLexicon = runCatching {
            fun read(name: String) = context.assets.open("zh/$name").bufferedReader().use { it.readText() }
            parse(read("hsk.tsv"), read("main_readings.tsv"), read("char_rank.txt"))
        }.getOrDefault(EMPTY)
    }
}

/** Ordering rules for Mandarin readings and meanings. Pure functions, unit-tested. */
object ZhRanking {
    /**
     * Readings of [word], everyday one first: the hand-checked main reading, then ordinary readings
     * before proper names (CC-CEDICT lists surnames and places first), then the reading Wiktionary's
     * translations use most ([links] by pinyin), then the one with more definitions. One per pinyin.
     */
    fun readings(word: String, readings: List<ZhReading>, links: Map<String, Int>, main: String?): List<ZhReading> {
        val merged = readings.groupBy { it.pinyin }.map { (_, same) ->
            val first = same.firstOrNull { it.simplified == word } ?: same.first()
            first.copy(definitions = same.flatMap { it.definitions }.distinct())
        }
        return merged.sortedWith(
            compareBy<ZhReading> { if (main != null && it.pinyin == main) 0 else 1 }
                .thenBy { if (it.simplified == word) 0 else 1 }
                .thenBy { if (it.pinyin.firstOrNull()?.isUpperCase() == true) 1 else 0 }
                .thenByDescending { links[it.pinyin] ?: 0 }
                .thenByDescending { it.definitions.size },
        )
    }

    private val parens = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val lead = Regex("^(to|a|an|the|one's|sb's|sth)\\s+")

    /** "to set off (fireworks)" → "set off". */
    fun core(def: String): String {
        var s = parens.replace(def, " ").lowercase().replace(Regex("\\s+"), " ").trim()
        while (true) { val t = lead.replaceFirst(s, ""); if (t == s) break; s = t }
        return s.trim(' ', '.', ';', ',')
    }

    fun isVerbDef(def: String): Boolean = def.trim().lowercase().startsWith("to ")

    /**
     * CC-CEDICT definitions split into single meanings, keeping their order: "to eat; to consume / to absorb"
     * → (0, "to eat"), (1, "to consume"), (10, "to absorb"). The key is group × 10 + position in the group.
     */
    fun meanings(defs: List<String>): List<Pair<Int, String>> =
        defs.flatMapIndexed { i, d -> d.split(';').map { it.trim() }.filter { it.isNotEmpty() }.take(10).mapIndexed { j, m -> (i * 10 + j) to m } }

    /**
     * Where an English word sits in the CC-CEDICT definitions: the key of an exact meaning ("put" in
     * "to put"), else 10,000 + the key of a meaning that contains it as a word, else null.
     */
    fun position(en: String, defs: List<String>): Int? {
        val w = en.lowercase().trim()
        val ms = meanings(defs)
        ms.forEach { (k, m) -> if (core(m) == w) return k }
        val re = Regex("(^|[^a-z])${Regex.escape(w)}([^a-z]|$)")
        ms.forEach { (k, m) -> if (re.containsMatchIn(core(m))) return 10_000 + k }
        return null
    }

    /** Wiktionary meanings in the order CC-CEDICT lists them (unmatched ones keep their order, at the end). */
    fun orderConcepts(concepts: List<Concept>, defs: List<String>): List<Concept> =
        concepts.withIndex().sortedBy { (i, c) -> position(c.enWord, defs) ?: (100_000 + i) }.map { it.value }

    /** Short CC-CEDICT meanings worth adding as senses ("release", "let go"), in order: (key, phrase, is a verb). */
    fun candidates(defs: List<String>, max: Int = 6): List<Triple<Int, String, Boolean>> =
        meanings(defs).mapNotNull { (k, d) ->
            val c = core(d)
            if (c.isEmpty() || c.length > 25 || c.count { it == ' ' } > 2 || c.startsWith("variant of") ||
                c.startsWith("surname") || c.startsWith("see ") || c.startsWith("old variant") || c.startsWith("used ") ||
                c.any { it.code > 0x2E7F }
            ) null else Triple(k, c, isVerbDef(d))
        }.distinctBy { it.second }.take(max)

    fun hskLabel(level: Int): String = if (level >= 7) "HSK 7–9" else "HSK $level"
}
