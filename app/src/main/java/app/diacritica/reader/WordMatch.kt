package app.diacritica.reader

import app.diacritica.data.Lang
import app.diacritica.data.TextNorm

/**
 * Finds the word in the other language's sentence that translates a long-pressed word, using the dictionary's
 * translations as candidates. Offline and approximate: exact words first, then the same stem
 * (pêcheur ~ pêcheurs, regarder ~ regardait, Fischer ~ Fischers). Returns the text as it appears in the sentence.
 */
object WordMatch {
    private val word = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

    fun find(sentence: String, lang: Lang, candidates: List<String>): String? {
        val cands = candidates.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cands.isEmpty() || sentence.isBlank()) return null
        if (lang == Lang.ZH) {
            // Chinese has no spaces: the longest candidate that appears in the sentence.
            return cands.filter { it in sentence }.maxByOrNull { it.length }
        }
        val tokens = word.findAll(sentence).map { m ->
            // "l'homme" → match on "homme", but highlight what's in the text.
            val t = m.value
            val core = t.substringAfterLast('\'').substringAfterLast('’').ifEmpty { t }
            Triple(core, TextNorm.normalize(core), core)
        }.toList()
        if (tokens.isEmpty()) return null
        val normSentence = " " + tokens.joinToString(" ") { it.second } + " "

        // 1) Whole phrase or word, exactly (ignoring case and accents).
        for (c in cands.sortedByDescending { it.length }) {
            val parts = word.findAll(c).map { TextNorm.normalize(it.value) }.toList().filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val phrase = parts.joinToString(" ")
            if (" $phrase " in normSentence) {
                val first = tokens.indexOfFirst { it.second == parts.first() }
                if (first >= 0) {
                    val last = (first + parts.size - 1).coerceAtMost(tokens.lastIndex)
                    return if (parts.size == 1) tokens[first].third
                    else sentence.substring(sentence.indexOf(tokens[first].third), sentence.indexOf(tokens[last].third) + tokens[last].third.length)
                }
            }
        }
        // 2) Same stem: the longest content word of each candidate against each sentence word.
        var best: Pair<String, Int>? = null
        for (c in cands) {
            val key = word.findAll(c).map { TextNorm.normalize(it.value) }.filter { it.length >= 3 }.maxByOrNull { it.length } ?: continue
            for ((_, norm, original) in tokens) {
                val p = commonPrefix(key, norm)
                val shorter = minOf(key.length, norm.length)
                if (p >= 4 && p >= shorter * 0.6 && (best == null || p > best.second)) best = original to p
            }
        }
        return best?.first
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}
