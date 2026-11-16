package app.diacritica.data

/**
 * Finds words through their English definitions when Wiktionary has no translation table entry:
 * French "filou" is defined as "trickster", so it can stand in for "trickster" in French.
 */
object GlossMatch {
    private val parens = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val lead = Regex("^(to|a|an|the)\\s+")

    /** The definition's items: "joker; trickster; prankster" → [joker, trickster, prankster]. */
    fun items(gloss: String): List<String> =
        parens.replace(gloss.lowercase(), " ").split(';', ',').map { lead.replace(it.trim(), "").trim().trimEnd('.') }.filter { it.isNotEmpty() }

    /**
     * How well [gloss] defines the English [word] (lower is better), or null if it doesn't:
     * 0 = the whole definition ("trickster"); 1–5 = one of a list, earlier is better ("trickster, swindler");
     * 10+ = inside a phrase ("confidence trickster"). A plural counts ("rascals").
     */
    fun score(gloss: String, word: String): Int? {
        val w = word.lowercase().trim()
        if (w.isEmpty()) return null
        val its = items(gloss)
        val exact = its.indexOfFirst { it == w || it == w + "s" || it == w + "es" }
        if (exact >= 0) return if (its.size == 1) 0 else 1 + minOf(exact, 4)
        val re = Regex("(^|[^a-z])${Regex.escape(w)}(s|es)?([^a-z]|$)")
        val inside = its.indexOfFirst { re.containsMatchIn(it) }
        return if (inside >= 0) 10 + minOf(inside, 9) else null
    }

    data class Candidate(val word: String, val pos: String?, val gender: String?, val gloss: String)

    /**
     * Best [max] distinct words for [word]. A different part of speech than [pos] costs 5 points and a
     * long idiom (four words or more) 3, so single words come first.
     */
    fun rank(candidates: List<Candidate>, word: String, pos: String?, max: Int = 3): List<Candidate> =
        candidates.mapNotNull { c ->
            score(c.gloss, word)?.let { s ->
                val posCost = if (pos != null && c.pos != null && c.pos != pos) 5 else 0
                val idiomCost = if (c.word.count { it == ' ' } >= 3) 3 else 0
                (s + posCost + idiomCost) to c
            }
        }
            .sortedWith(compareBy<Pair<Int, Candidate>> { it.first }.thenBy { it.second.gloss.length }.thenBy { it.second.word.length })
            .distinctBy { it.second.word }
            .take(max)
            .map { it.second }
}
