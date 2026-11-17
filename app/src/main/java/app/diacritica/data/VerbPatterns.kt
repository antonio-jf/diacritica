package app.diacritica.data

import android.database.sqlite.SQLiteDatabase

/**
 * How a German verb is used in one meaning: reflexive or not, and the preposition and case (or the bare case) its
 * object takes. From Wiktionary's labels, e.g. "(reflexive) to look forward to [with auf (+ accusative)]" →
 * sich freuen auf + Akk.
 */
data class VerbPattern(val reflexive: Boolean, val prep: String?, val case: String?, val gloss: String) {
    /** "sich freuen auf + Akk." */
    fun text(verb: String): String = listOfNotNull(
        "sich".takeIf { reflexive }, verb, prep,
        case?.let { "+ " + (CASE_ABBR[it] ?: it) },
    ).joinToString(" ")

    companion object {
        val CASE_ABBR = mapOf("accusative" to "Akk.", "dative" to "Dat.", "genitive" to "Gen.")
    }
}

object VerbPatterns {
    /** Prepositions a German verb can govern (so "[with an infinitive]" and the like aren't taken for one). */
    private val PREPS = setOf(
        "an", "auf", "aus", "bei", "bis", "durch", "für", "gegen", "hinter", "in", "mit", "nach", "neben", "ohne",
        "seit", "über", "um", "unter", "von", "vor", "wegen", "zu", "zwischen", "gegenüber", "außer", "entlang", "trotz",
        "statt", "während", "als",
    )
    private val CASES = "accusative|dative|genitive"
    private val label = Regex("^\\(([^)]*)\\)")
    private val bracket = Regex("\\[([^\\]]*)]")
    private val prepCase = Regex("^(\\p{L}+)\\s*\\(\\+\\s*($CASES)\\)$")
    private val bareCase = Regex("^\\+?\\s*($CASES)$")
    private val labelCase = Regex("\\+\\s*($CASES)|with ($CASES)")

    /**
     * Patterns in one sense. [raw] is its raw gloss (with labels and "[with …]"), [tags] its tags, [gloss] the clean
     * gloss shown next to the pattern. Empty when the sense says nothing about reflexivity or objects.
     */
    fun parse(raw: String?, tags: List<String>, gloss: String): List<VerbPattern> {
        val r = raw.orEmpty()
        val lab = label.find(r)?.groupValues?.get(1)?.lowercase().orEmpty()
        val reflexive = "reflexive" in tags || Regex("\\breflexive\\b").containsMatchIn(lab)
        val short = gloss.trim().let { if (it.length > 80) it.take(77) + "…" else it }
        val out = LinkedHashSet<VerbPattern>()
        for (m in bracket.findAll(r)) {
            val body = m.groupValues[1].trim().removePrefix("with").trim()
            for (part in body.split(Regex("\\s+or\\s+|;|,")).map { it.trim() }.filter { it.isNotEmpty() }) {
                prepCase.matchEntire(part)?.let { pm ->
                    val p = pm.groupValues[1].lowercase()
                    if (p in PREPS) out += VerbPattern(reflexive, p, pm.groupValues[2], short)
                } ?: bareCase.matchEntire(part)?.let { cm -> out += VerbPattern(reflexive, null, cm.groupValues[1], short) }
                    ?: part.lowercase().takeIf { it in PREPS }?.let { p -> out += VerbPattern(reflexive, p, null, short) }
            }
        }
        if (out.isEmpty()) labelCase.find(lab)?.let { m ->
            out += VerbPattern(reflexive, null, m.groupValues[1].ifEmpty { m.groupValues[2] }, short)
        }
        if (out.isEmpty() && reflexive) out += VerbPattern(true, null, null, short)
        return out.toList()
    }

    const val CREATE = "CREATE TABLE IF NOT EXISTS verb_pattern (entry_id INTEGER NOT NULL, ord INTEGER NOT NULL, " +
        "reflexive INTEGER NOT NULL, prep TEXT, gcase TEXT, gloss TEXT)"

    /** Patterns stored for an entry; empty when the dictionary has none (older packs have no table). */
    fun load(db: SQLiteDatabase, entryId: Long): List<VerbPattern> = runCatching {
        db.rawQuery("SELECT reflexive, prep, gcase, gloss FROM verb_pattern WHERE entry_id = ? ORDER BY ord", arrayOf(entryId.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(VerbPattern(c.getInt(0) == 1, c.getStringOrNull(1), c.getStringOrNull(2), c.getStringOrNull(3).orEmpty()))
            }
        }
    }.getOrDefault(emptyList())

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}
