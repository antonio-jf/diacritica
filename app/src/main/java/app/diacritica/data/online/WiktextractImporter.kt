package app.diacritica.data.online

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import app.diacritica.data.WordExtras
import app.diacritica.data.ZhClean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Inserts Wiktextract (kaikki.org) JSON objects into the dictionary database.
 * A Kotlin port of `Builder.add_entry` / `Builder.add_concepts` in pipeline/build_dictionary.py;
 * keep the two in step.
 */
object WiktextractImporter {
    // Proper nouns ("name": Germany, Paris) are kept, as in the pipeline.
    private val skipPos = setOf(
        "character", "symbol", "suffix", "prefix", "infix", "affix",
        "interfix", "circumfix", "punct", "romanization", "abbrev", "soft-redirect",
    )
    private val junkFormTags = setOf("table-tags", "inflection-template", "class", "romanization")
    private val wiktCodes = mapOf("fr" to Lang.FR, "de" to Lang.DE, "it" to Lang.IT, "es" to Lang.ES, "cmn" to Lang.ZH)
    private val headGender = Regex("^\\s+(m|f|n|c)(?:\\s*(?:or|,|/)\\s*(m|f|n))?(\\s+pl\\b)?(?=\\s|$|\\()")
    private val articles = setOf("der", "die", "das", "den", "dem", "des", "le", "la", "les", "l'", "il", "lo", "gli", "i", "el", "los", "las")
    private const val MAX_SENSES = 8
    private const val MAX_FORMS = 80
    private const val MAX_EXAMPLE = 140

    // ------------------------------------------------------------ JSON helpers
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
    private fun JsonObject.objs(key: String): List<JsonObject> = arr(key).filterIsInstance<JsonObject>()
    private fun JsonObject.strs(key: String): List<String> = arr(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /** Removes everything previously stored for this word, so a fresh download replaces it. */
    fun deleteWord(db: SQLiteDatabase, lang: Lang, word: String) {
        ensureExtraTable(db)
        db.execSQL("DELETE FROM extra WHERE entry_id IN (SELECT id FROM entry WHERE lang = ? AND word = ?)", arrayOf(lang.code, word))
        db.execSQL("DELETE FROM sense WHERE entry_id IN (SELECT id FROM entry WHERE lang = ? AND word = ?)", arrayOf(lang.code, word))
        db.execSQL("DELETE FROM form WHERE entry_id IN (SELECT id FROM entry WHERE lang = ? AND word = ?)", arrayOf(lang.code, word))
        db.execSQL("DELETE FROM entry WHERE lang = ? AND word = ?", arrayOf(lang.code, word))
        if (lang == Lang.EN) {
            db.execSQL("DELETE FROM translation WHERE concept_id IN (SELECT id FROM concept WHERE en_word = ?)", arrayOf(word))
            db.execSQL("DELETE FROM concept WHERE en_word = ?", arrayOf(word))
        }
    }

    /** Dictionaries built before etymologies were added have no `extra` table; downloads add it. */
    fun ensureExtraTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS extra (entry_id INTEGER PRIMARY KEY, etymology TEXT, synonyms TEXT)")
    }

    /** Imports all lines for one word in one language. Returns the number of entries stored. */
    fun importWord(db: SQLiteDatabase, lang: Lang, lines: List<JsonObject>): Int {
        ensureExtraTable(db)
        var count = 0
        var rank = 0
        for (e in lines) {
            val code = e.str("lang_code") ?: lang.code
            if (code != lang.code) continue
            if (lang == Lang.EN) rank = addConcepts(db, e, rank)
            if (addEntry(db, lang, e)) count++
        }
        return count
    }

    private fun addEntry(db: SQLiteDatabase, lang: Lang, e: JsonObject): Boolean {
        val word = e.str("word")?.trim().orEmpty()
        val pos = e.str("pos")
        if (word.isEmpty() || pos in skipPos) return false
        val senses = senseRows(e)
        if (senses.isEmpty()) return false
        val forms = cleanForms(e)
        val id = db.insert("entry", null, ContentValues().apply {
            put("lang", lang.code); put("word", word); put("norm", TextNorm.normalize(word)); put("pos", pos)
            put("gender", entryGender(e)); put("ipa", firstIpa(e)); put("plural", entryPlural(forms, lang))
        })
        senses.forEach { s ->
            db.insert("sense", null, ContentValues().apply {
                put("entry_id", id); put("ord", s.ord); put("gloss", s.gloss); put("example", s.example)
                put("example_en", s.exampleEn); put("form_of", s.formOf)
            })
        }
        forms.forEach { (f, t) ->
            db.insert("form", null, ContentValues().apply { put("entry_id", id); put("form", f); put("tags", t) })
        }
        val ety = WordExtras.etymology(e)
        val syns = WordExtras.join(WordExtras.synonyms(e))
        if (ety != null || syns != null) {
            db.insert("extra", null, ContentValues().apply { put("entry_id", id); put("etymology", ety); put("synonyms", syns) })
        }
        return true
    }

    private fun addConcepts(db: SQLiteDatabase, e: JsonObject, startRank: Int): Int {
        val word = e.str("word")?.trim().orEmpty()
        val pos = e.str("pos")
        if (word.isEmpty() || pos in skipPos) return startRank
        val groups = LinkedHashMap<String, MutableList<Pair<Lang, JsonObject>>>()
        fun collect(trs: List<JsonObject>, fallback: String?) {
            for (t in trs) {
                val lang = trLang(t) ?: continue
                if (t.str("word").isNullOrBlank()) continue
                val label = (t.str("sense") ?: fallback ?: "").trim()
                groups.getOrPut(label) { mutableListOf() } += lang to t
            }
        }
        collect(e.objs("translations"), null)
        for (s in e.objs("senses")) {
            val trs = s.objs("translations")
            if (trs.isNotEmpty()) collect(trs, s.strs("glosses").joinToString("; ").take(80).ifBlank { null })
        }
        var rank = startRank
        val norm = TextNorm.normalize(word)
        for ((label, items) in groups) {
            val cid = db.insert("concept", null, ContentValues().apply {
                put("en_word", word); put("en_norm", norm); put("pos", pos); put("label", label); put("rank", rank)
            })
            rank++
            val seen = HashSet<String>()
            items.forEachIndexed { i, (lang, t) ->
                var tw = t.str("word")!!.trim()
                var roman = t.str("roman")
                if (lang == Lang.ZH) {
                    tw = ZhClean.split(tw)?.simplified ?: return@forEachIndexed
                    zhSimplified(db, tw)?.let { (simp, marks) -> tw = simp; if (roman == null) roman = marks }
                }
                if (!seen.add(lang.code + ":" + tw)) return@forEachIndexed
                db.insert("translation", null, ContentValues().apply {
                    put("concept_id", cid); put("lang", lang.code); put("word", tw); put("norm", TextNorm.normalize(tw))
                    put("gender", genderFromTags(t.strs("tags"))); put("roman", roman); put("ord", i)
                })
            }
        }
        return rank
    }

    private fun zhSimplified(db: SQLiteDatabase, w: String): Pair<String, String>? =
        db.rawQuery("SELECT simp, pinyin FROM zh WHERE simp = ?1 OR trad = ?1 ORDER BY (simp = ?1) DESC LIMIT 1", arrayOf(w)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }

    private fun trLang(t: JsonObject): Lang? {
        val code = t.str("lang_code") ?: t.str("code")
        wiktCodes[code]?.let { return it }
        val name = t.str("lang").orEmpty()
        if (code == "zh" && "Mandarin" in name) return Lang.ZH
        if (name == "Mandarin" || name == "Chinese Mandarin") return Lang.ZH
        return null
    }

    fun genderFromTags(tags: List<String>): String? {
        val set = tags.toSet()
        val g = listOf("masculine" to "m", "feminine" to "f", "neuter" to "n", "common" to "c")
            .filter { it.first in set }.map { it.second }
        if (g.isEmpty()) return null
        var s = g.joinToString("/")
        if ("plural" in set && "singular" !in set) s += " pl"
        return s
    }

    private fun entryGender(e: JsonObject): String? {
        genderFromTags(e.strs("tags"))?.let { return it }
        val word = e.str("word").orEmpty()
        for (ht in e.objs("head_templates")) {
            val exp = ht.str("expansion").orEmpty()
            if (exp.startsWith(word)) {
                val m = headGender.find(exp.substring(word.length)) ?: continue
                if (m.range.first != 0) continue
                val g2 = m.groupValues[2]
                return m.groupValues[1] + (if (g2.isNotEmpty()) "/$g2" else "") + (if (m.groupValues[3].isNotEmpty()) " pl" else "")
            }
        }
        return e.objs("senses").firstOrNull()?.let { genderFromTags(it.strs("tags")) }
    }

    private fun stripArticle(form: String): String {
        val parts = form.split(" ", limit = 2)
        return if (parts.size == 2 && parts[0].lowercase() in articles) parts[1] else form
    }

    private fun cleanForms(e: JsonObject): List<Pair<String, String>> {
        val word = e.str("word")
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<Pair<String, String>>()
        for (f in e.objs("forms")) {
            val form = f.str("form")?.trim().orEmpty()
            val tags = f.strs("tags")
            if (form.isEmpty() || (form == word && tags.isEmpty()) || tags.any { it in junkFormTags } || form == "-" || form == "—") continue
            val key = form to tags.joinToString(",")
            if (seen.add(key)) out += key
            if (out.size >= MAX_FORMS) break
        }
        return out
    }

    private fun entryPlural(forms: List<Pair<String, String>>, lang: Lang): String? {
        var best: String? = null
        for ((form, tags) in forms) {
            val ts = tags.split(",").toSet()
            if ("plural" !in ts) continue
            if (lang == Lang.DE) {
                if ("nominative" in ts && "definite" !in ts && "indefinite" !in ts) return stripArticle(form)
                if (best == null && "nominative" in ts) best = stripArticle(form)
            } else if (best == null && (ts.all { it in setOf("plural", "masculine", "feminine") } || ts.size <= 2)) {
                best = stripArticle(form)
            }
        }
        return best
    }

    private fun firstIpa(e: JsonObject): String? = e.objs("sounds").firstNotNullOfOrNull { it.str("ipa") }

    private data class SenseRow(val ord: Int, val gloss: String, val example: String?, val exampleEn: String?, val formOf: String?)

    private fun senseRows(e: JsonObject): List<SenseRow> =
        e.objs("senses").take(MAX_SENSES).mapIndexedNotNull { i, s ->
            val gloss = (s.strs("glosses").ifEmpty { s.strs("raw_glosses") }).joinToString("; ")
            if (gloss.isEmpty()) return@mapIndexedNotNull null
            val ex = s.objs("examples").firstOrNull { (it.str("text")?.trim()?.length ?: 0) in 1..MAX_EXAMPLE }
            val formOf = (s.objs("form_of") + s.objs("alt_of")).firstOrNull()?.str("word")
            SenseRow(i, gloss, ex?.str("text")?.trim(), ex?.let { it.str("english") ?: it.str("translation") }, formOf)
        }

    /** English words a foreign gloss points to: "teacher (person who teaches)" → ["teacher"]. */
    fun englishCandidates(lines: List<JsonObject>): List<String> {
        val out = LinkedHashSet<String>()
        for (e in lines) for (s in e.objs("senses")) {
            for (g in s.strs("glosses")) {
                g.split(';', ',').map { it.substringBefore('(').trim().removePrefix("to ").removePrefix("a ").removePrefix("an ").removePrefix("the ").trim() }
                    .filter { it.isNotEmpty() && it.length <= 30 && it.count { c -> c == ' ' } <= 2 }
                    .forEach { out += it }
            }
        }
        return out.take(3)
    }
}
