package app.diacritica.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Etymology and synonyms from a Wiktextract entry. A Kotlin port of `etymology_text` /
 * `synonym_list` in pipeline/build_dictionary.py; keep the two in step.
 */
object WordExtras {
    const val MAX_ETYMOLOGY = 500
    const val MAX_SYNONYMS = 12
    private val spaces = Regex("\\s+")

    fun etymology(raw: String?): String? {
        val t = raw?.replace(spaces, " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= MAX_ETYMOLOGY) return t
        val cut = t.substring(0, MAX_ETYMOLOGY)
        val dot = cut.lastIndexOf(". ")
        return if (dot > MAX_ETYMOLOGY / 2) cut.substring(0, dot + 1) else cut.trimEnd() + "…"
    }

    fun etymology(e: JsonObject): String? = etymology((e["etymology_text"] as? JsonPrimitive)?.contentOrNull)

    fun synonyms(e: JsonObject): List<String> {
        val word = (e["word"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val groups = buildList {
            add(e["synonyms"])
            (e["senses"] as? JsonArray)?.forEach { s -> add((s as? JsonObject)?.get("synonyms")) }
        }
        val out = ArrayList<String>()
        for (g in groups) for (syn in (g as? JsonArray).orEmpty()) {
            val w = ((syn as? JsonObject)?.get("word") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (w.isEmpty() || w == word || w in out || w.length > 40 || w.startsWith("Thesaurus:")) continue
            out += w
            if (out.size >= MAX_SYNONYMS) return out
        }
        return out
    }

    /** Stored form of a synonym list ("a|b|c"), and back. */
    fun join(list: List<String>): String? = list.joinToString("|").ifEmpty { null }
    fun split(stored: String?): List<String> = stored?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/** A German separable verb: [prefix] detaches in main clauses, e.g. anfangen → "fängt … an". */
data class SeparableVerb(val prefix: String, val stem: String, val example: String?) {
    /** "fängt … an", or "… an" when no conjugated form is known. */
    val pattern: String get() = (example?.substringBefore(' ') ?: "…") + " … " + prefix
}

object SeparableVerbs {
    /** Prefixes that can detach. Longest first so "zurück" wins over "zu". */
    val PREFIXES: List<String> = listOf(
        "zusammen", "entgegen", "zurecht", "wieder", "zurück", "weiter", "voraus", "vorbei", "vorher",
        "heraus", "herein", "herauf", "herunter", "hinaus", "hinein", "hinauf", "hinunter", "herum", "heran",
        "nieder", "statt", "durch", "unter", "fort", "fest", "frei", "hoch", "nach", "über", "teil", "kennen",
        "kaputt", "dabei", "dazu", "davon", "empor", "her", "hin", "los", "mit", "vor", "weg", "auf", "aus",
        "bei", "ein", "dar", "um", "ab", "an", "zu",
    ).sortedByDescending { it.length }

    /**
     * Decides from the dictionary's conjugation table: a verb is separable when one of its finite
     * forms is "<stem> <prefix>" (fängt an) and the infinitive starts with that prefix. Verbs with
     * both readings (übersetzen, umfahren) are flagged when the table lists a split form.
     * Returns null for non-separable verbs and when the table has no finite forms to judge by.
     */
    fun detect(word: String, pos: String?, forms: List<FormRow>): SeparableVerb? {
        if (pos != null && pos != "verb") return null
        val w = word.trim().lowercase().removePrefix("sich ").trim()
        var best: SeparableVerb? = null
        for (f in forms) {
            val parts = f.form.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size != 2) continue
            val prefix = parts[1].lowercase()
            if (prefix !in PREFIXES || !w.startsWith(prefix) || w.length <= prefix.length + 2) continue
            val tags = f.tags.split(',').toSet()
            val cand = SeparableVerb(prefix, w.substring(prefix.length), f.form.trim())
            val thirdPresent = "third-person" in tags && "singular" in tags && "present" in tags
            if (thirdPresent) return cand
            if (best == null) best = cand
        }
        return best
    }

    fun detect(e: EntryDetail): SeparableVerb? = if (e.lang == Lang.DE) detect(e.word, e.pos, e.forms) else null
}
