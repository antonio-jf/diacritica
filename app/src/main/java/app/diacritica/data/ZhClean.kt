package app.diacritica.data

/**
 * Wiktionary writes Mandarin translations as "trad /simp" in one string ("老師 /老师"), sometimes with
 * notes ("(一)劑 /剂", "-夫人"). This splits them into a clean simplified headword (+ traditional).
 * Mirrors `clean_zh()` in pipeline/build_dictionary.py.
 */
object ZhClean {
    private val parens = Regex("[(（][^)）]*[)）]")

    data class Split(val simplified: String, val traditional: String?)

    fun split(raw: String): Split? {
        val parts = raw.split('/').map { clean(it) }.filter { TextNorm.containsHan(it) }
        if (parts.isEmpty()) return null
        val simp = parts.last()
        val trad = parts.first().takeIf { it != simp && it.length == simp.length }
        return Split(simp, trad)
    }

    private fun clean(s: String): String =
        parens.replace(s, "").trim().trim('-', '…', '.', ' ')

    /** True when the string still needs cleaning. */
    fun needsCleaning(raw: String): Boolean =
        raw.contains('/') || raw.contains('(') || raw.contains('（') || raw.startsWith("-") || raw.endsWith("-") || raw.contains("...")
}
