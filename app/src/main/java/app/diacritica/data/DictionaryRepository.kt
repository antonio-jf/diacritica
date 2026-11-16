package app.diacritica.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lookup logic over the offline dictionary.
 *
 * Model: every English Wiktionary sense with a translation table is a [Concept]. A query in any
 * language is mapped to concepts (English headword, or a translation in another language), and the
 * selected concept is shown in every active language.
 */
class DictionaryRepository(
    private val database: DictionaryDatabase,
    zhLexicon: Lazy<ZhLexicon> = lazyOf(ZhLexicon.EMPTY),
) {
    /** HSK levels and everyday readings for Mandarin (bundled; the dictionary file has neither). */
    private val zhLex by zhLexicon


    suspend fun lookup(
        query: String,
        source: Lang?,
        active: List<Lang>,
        script: ScriptPreference,
        selected: Int = 0,
    ): LookupResult = withContext(Dispatchers.IO) {
        val db = database.get()
        val q = query.trim()
        val candidates = when {
            // Chinese characters are always Mandarin, whatever source language is selected.
            TextNorm.containsHan(q) -> listOf(Lang.ZH)
            source != null -> listOf(source)
            else -> active.ifEmpty { listOf(Lang.EN) }
        }
        // Find every language where the query matches; the first in the user's order wins.
        val matches = candidates.map { it to findConcepts(db, it, q) }.filter { it.second.isNotEmpty() }
        val (lang, hit) = matches.firstOrNull() ?: (candidates.first() to emptyList<Pair<Concept, String?>>())
        val concepts = hit.map { it.first }.distinctBy { it.id }.ifEmpty {
            // A Mandarin word with no link to the other languages yet: still show it, with its CEDICT English as the
            // meaning, so each language offers "Suggest a translation" instead of an empty page.
            if (lang == Lang.ZH) listOfNotNull(cedictConcept(db, q)) else emptyList()
        }
        val lemma = hit.firstOrNull()?.second
        val headwordText = lemma ?: q
        val headword = headwordInfo(db, lang, headwordText, concepts.firstOrNull(), script)
        val sel = selected.coerceIn(0, (concepts.size - 1).coerceAtLeast(0))
        val results = concepts.getOrNull(sel)?.let { c ->
            active.filter { it != lang }.map { LangResult(it, wordsFor(db, c, it, script)) }
        } ?: emptyList()
        LookupResult(
            query = q,
            source = lang,
            alsoFoundIn = matches.drop(1).map { it.first },
            headword = headword,
            concepts = concepts,
            selected = sel,
            results = results,
            redirectedFrom = if (lemma != null) q else null,
            forms = if (lang == Lang.ZH) emptyList() else formNotes(db, lang, q),
            suggestions = if (concepts.isEmpty()) suggestions(db, lang, q) else emptyList(),
            zhAlternatives = if (lang == Lang.ZH) zhAlternatives(db, q) else emptyList(),
        )
    }

    // ------------------------------------------------------------------ query → concepts

    /** Returns concepts, each paired with a non-null value when found through an inflected form. */
    private fun findConcepts(db: SQLiteDatabase, lang: Lang, q: String): List<Pair<Concept, String?>> {
        if (q.isBlank()) return emptyList()
        val direct = when (lang) {
            Lang.EN -> conceptsByEnglish(db, TextNorm.normalize(q))
            // Only the best-ranked Chinese word: homophones ("laoshi" = 老师 / 老实 / 老是) must not mix meanings.
            Lang.ZH -> zhWords(db, q).firstOrNull()?.let { w -> zhConcepts(db, w) } ?: emptyList()
            else -> conceptsByTranslation(db, lang, TextNorm.normalize(q), byWord = false)
        }
        if (direct.isNotEmpty()) return direct.map { it to null }
        if (lang == Lang.ZH) return emptyList()
        // Inflected form → lemma (chiavi → chiave, keys → key)
        val lemma = lemmaFor(db, lang, q) ?: return emptyList()
        val viaLemma = if (lang == Lang.EN) conceptsByEnglish(db, TextNorm.normalize(lemma))
        else conceptsByTranslation(db, lang, TextNorm.normalize(lemma), byWord = false)
        return viaLemma.map { it to lemma }
    }

    private fun conceptsByEnglish(db: SQLiteDatabase, norm: String): List<Concept> =
        db.rawQuery(
            "SELECT id, en_word, pos, label FROM concept WHERE en_norm = ? ORDER BY rank LIMIT 30",
            arrayOf(norm),
        ).use { it.mapConcepts() }

    private fun conceptsByTranslation(db: SQLiteDatabase, lang: Lang, key: String, byWord: Boolean): List<Concept> {
        val col = if (byWord) "t.word" else "t.norm"
        return db.rawQuery(
            "SELECT DISTINCT c.id, c.en_word, c.pos, c.label FROM translation t " +
                "JOIN concept c ON c.id = t.concept_id WHERE t.lang = ? AND $col = ? " +
                "ORDER BY t.ord, c.rank LIMIT 30",
            arrayOf(lang.code, key),
        ).use { it.mapConcepts() }
    }

    private fun Cursor.mapConcepts(): List<Concept> {
        val out = ArrayList<Concept>()
        while (moveToNext()) out += Concept(getLong(0), getString(1), getStringOrNull(2), getStringOrNull(3) ?: "")
        return out
    }

    /**
     * What [q] is a form of, from its Wiktionary "form of" senses: "nahm" → (nehmen, "first/third-person
     * singular preterite"). Several when ambiguous ("fui": ir and ser). Empty for base forms.
     */
    private fun formNotes(db: SQLiteDatabase, lang: Lang, q: String): List<FormNote> =
        db.rawQuery(
            "SELECT s.form_of, s.gloss FROM entry e JOIN sense s ON s.entry_id = e.id " +
                "WHERE e.lang = ? AND e.norm = ? AND s.form_of IS NOT NULL AND s.form_of != e.word ORDER BY (e.word = ?) DESC, e.id, s.ord LIMIT 6",
            arrayOf(lang.code, TextNorm.normalize(q), q),
        ).use { c -> buildList { while (c.moveToNext()) add(FormNoteText.parse(c.getString(0), c.getString(1))) } }
            .distinctBy { it.lemma to it.description }.take(3)

    /**
     * Every single-word form of [word] from its conjugation/declension tables, plus the word itself:
     * nehmen → nehmen, nahm, genommen, nimmt… Articles in table forms are dropped ("die Häuser" → Häuser).
     */
    suspend fun surfaceForms(lang: Lang, word: String): Set<String> = withContext(Dispatchers.IO) {
        val db = database.get()
        val out = HashSet<String>()
        out += TextNorm.normalize(word)
        db.rawQuery(
            "SELECT f.form FROM entry e JOIN form f ON f.entry_id = e.id WHERE e.lang = ? AND e.norm = ? LIMIT 400",
            arrayOf(lang.code, TextNorm.normalize(word)),
        ).use { c ->
            while (c.moveToNext()) {
                val parts = c.getString(0).trim().split(' ').filter { it.isNotEmpty() }
                val single = when {
                    parts.size == 1 -> parts[0]
                    parts.size == 2 && parts[0].lowercase() in formArticles -> parts[1]
                    else -> null
                }
                single?.let { out += TextNorm.normalize(it) }
            }
        }
        out
    }

    private val formArticles = setOf("der", "die", "das", "den", "dem", "des", "le", "la", "les", "l'", "il", "lo", "gli", "i", "el", "los", "las")

    private fun lemmaFor(db: SQLiteDatabase, lang: Lang, q: String): String? =
        db.rawQuery(
            "SELECT s.form_of FROM entry e JOIN sense s ON s.entry_id = e.id " +
                "WHERE e.lang = ? AND e.norm = ? AND s.form_of IS NOT NULL LIMIT 1",
            arrayOf(lang.code, TextNorm.normalize(q)),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * Simplified headwords matching hanzi (simplified or traditional) or pinyin (tone marks, tone numbers,
     * or no tones), most common first (number of Wiktionary senses linking to it), then HSK level, then length.
     */
    private fun zhWords(db: SQLiteDatabase, q: String, limit: Int = 10): List<String> {
        // Commonness proxy: how many Wiktionary senses use the word as their Mandarin translation.
        // Fetch more than needed, then let the bundled HSK level break ties between equally linked words.
        val wide = "ORDER BY (SELECT COUNT(*) FROM translation t WHERE t.lang = 'zh' AND t.norm = zh.simp) DESC, " +
            "length(simp), id LIMIT ${maxOf(limit, 30)}"
        fun run(where: String, arg: String) = db.rawQuery(
            "SELECT simp, (SELECT COUNT(*) FROM translation t WHERE t.lang = 'zh' AND t.norm = zh.simp) FROM zh WHERE $where GROUP BY simp $wide",
            arrayOf(arg),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }.thenBy { zhLex.hsk(it.value.first) ?: 99 }.thenBy { it.index })
            .map { it.value.first }.take(limit)
        return when {
            TextNorm.containsHan(q) -> run("simp = ?1 OR trad = ?1", q.trim())
            TextNorm.looksLikeNumberedPinyin(q) -> run("pinyin_num = ?1 OR pinyin_num LIKE ?1 || '_'", TextNorm.pinyinNumKey(q))
            TextNorm.looksLikePinyin(q) -> {
                // With tone marks ("lǎoshī"), match the tones exactly first.
                val exact = if (TextNorm.hasToneMarks(q)) {
                    run("replace(replace(lower(pinyin), ' ', ''), char(39), '') = ?1", TextNorm.pinyinMarkedKey(q))
                } else emptyList()
                exact.ifEmpty { run("pinyin_norm = ?1", TextNorm.pinyinToneless(q)) }
            }
            else -> emptyList()
        }
    }

    /**
     * Meanings of a Chinese word: Wiktionary's links in the order CC-CEDICT lists the definitions
     * (放: put, place… rather than "lay" first), plus up to three CC-CEDICT definitions Wiktionary
     * doesn't link (放: release, let go). Verb definitions ("to …") only match English verbs.
     */
    private fun zhConcepts(db: SQLiteDatabase, w: String): List<Concept> {
        val linked = conceptsByTranslation(db, Lang.ZH, w, byWord = true)
        if (linked.isEmpty()) return conceptsViaCedict(db, w)
        val defs = zhReadings(db, w).firstOrNull()?.definitions.orEmpty()
        if (defs.isEmpty()) return linked
        val placed = ZhRanking.orderConcepts(linked, defs).mapIndexed { i, c -> (ZhRanking.position(c.enWord, defs) ?: (100_000 + i)) to c }
        val covered = linked.map { it.enWord.lowercase() }.toMutableSet()
        val added = ArrayList<Pair<Int, Concept>>()
        for ((i, phrase, verb) in ZhRanking.candidates(defs)) {
            if (added.size >= 3) break
            if (phrase in covered) continue
            val c = conceptsByEnglish(db, TextNorm.normalize(phrase))
                .firstOrNull { it.pos == null || (it.pos == "verb") == verb } ?: continue
            if (placed.any { it.second.id == c.id } || added.any { it.second.id == c.id }) continue
            covered += phrase
            added += i to c
        }
        return (placed + added).sortedBy { it.first }.map { it.second }
    }

    /** Other Chinese words matching the same query (homophones), for the "Other matches" chips. */
    private fun zhAlternatives(db: SQLiteDatabase, q: String): List<ZhReading> =
        zhWords(db, q, limit = 9).drop(1).mapNotNull { zhReadings(db, it).firstOrNull() }

    /**
     * For Chinese words without a Wiktionary translation link: use CC-CEDICT's English definitions
     * ("teacher; instructor") to find the matching English senses.
     */
    private fun conceptsViaCedict(db: SQLiteDatabase, simp: String): List<Concept> =
        cedictEnglish(db, simp).flatMap { conceptsByEnglish(db, TextNorm.normalize(it)).take(2) }.distinctBy { it.id }.take(6)

    /**
     * The English words and short phrases in CC-CEDICT's definition of [simp]: 起床 → "get out of bed", "get up".
     * Phrases of up to four words are kept, since many everyday verbs are phrasal ("get up", "wake up").
     */
    private fun cedictEnglish(db: SQLiteDatabase, simp: String): List<String> {
        val defs = zhReadings(db, simp).firstOrNull()?.definitions ?: return emptyList()
        val parens = Regex("\\([^)]*\\)")
        return defs.asSequence()
            .flatMap { it.split(';', ',').asSequence() }
            .map { parens.replace(it, "").trim().removePrefix("to ").removePrefix("a ").removePrefix("an ").removePrefix("the ").trim() }
            .filter { it.isNotEmpty() && it.length <= 30 && it.count { ch -> ch == ' ' } <= 3 && !it.startsWith("variant of") && !it.startsWith("surname") && !it.startsWith("CL:") }
            .distinct().take(4).toList()
    }

    /** Stand-in meaning for a Mandarin word without Wiktionary links (negative id: no translations stored). */
    private fun cedictConcept(db: SQLiteDatabase, q: String): Concept? {
        val w = zhWords(db, q).firstOrNull() ?: return null
        val reading = zhReadings(db, w).firstOrNull() ?: return null
        val en = cedictEnglish(db, w).firstOrNull() ?: return null
        return Concept(id = -(w.hashCode().toLong() and 0x7fffffffL) - 1, enWord = en, pos = null,
            label = reading.definitions.take(3).joinToString("; "))
    }

    /** True for [cedictConcept]'s stand-in meanings. */
    fun isCedictOnly(c: Concept) = c.id < 0

    /**
     * English headwords to download for a Mandarin word that has no translations offline (the dictionary pack only
     * holds frequent English words, so phrasal verbs like "get up" can be missing). Fetching them from Wiktionary
     * brings their translation tables, which link 起床 to se lever / aufstehen / alzarsi / levantarse.
     */
    suspend fun englishForChinese(query: String): List<String> = withContext(Dispatchers.IO) {
        val db = database.get()
        val w = zhWords(db, query.trim()).firstOrNull() ?: return@withContext emptyList()
        cedictEnglish(db, w).filter { conceptsByEnglish(db, TextNorm.normalize(it)).isEmpty() }.take(3)
    }

    /** Chinese words whose CC-CEDICT definition is exactly this English word (fallback when Wiktionary has none). */
    private fun zhByEnglish(db: SQLiteDatabase, en: String): List<String> {
        val e = en.trim().lowercase()
        if (e.isEmpty() || e.contains('%') || e.contains('_')) return emptyList()
        val item = "('/' || replace(lower(defs), ' / ', '/') || '/')"
        return db.rawQuery(
            "SELECT simp FROM zh WHERE $item LIKE ?1 OR $item LIKE ?2 " +
                "GROUP BY simp ORDER BY (SELECT COUNT(*) FROM translation t WHERE t.lang = 'zh' AND t.norm = zh.simp) DESC, " +
                "hsk IS NULL, hsk, length(simp) LIMIT 2",
            arrayOf("%/$e/%", "%/to $e/%"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    private fun suggestions(db: SQLiteDatabase, lang: Lang, q: String): List<String> {
        val norm = TextNorm.normalize(q)
        if (norm.length < 2 && !TextNorm.containsHan(q)) return emptyList()
        val sql = when (lang) {
            Lang.EN -> "SELECT DISTINCT en_word FROM concept WHERE en_norm LIKE ? || '%' ORDER BY length(en_word) LIMIT 8"
            Lang.ZH -> return db.rawQuery(
                if (TextNorm.containsHan(q)) "SELECT DISTINCT simp FROM zh WHERE simp LIKE ?1 || '%' OR trad LIKE ?1 || '%' ORDER BY hsk IS NULL, hsk, length(simp) LIMIT 8"
                else "SELECT DISTINCT simp FROM zh WHERE pinyin_norm LIKE ?1 || '%' ORDER BY hsk IS NULL, hsk, length(simp) LIMIT 8",
                arrayOf(if (TextNorm.containsHan(q)) q.trim() else TextNorm.pinyinToneless(q)),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            else -> "SELECT DISTINCT word FROM translation WHERE lang = '${lang.code}' AND norm LIKE ? || '%' " +
                "ORDER BY length(word) LIMIT 8"
        }
        return db.rawQuery(sql, arrayOf(norm)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    // ------------------------------------------------------------------ concept → words

    private fun wordsFor(db: SQLiteDatabase, c: Concept, lang: Lang, script: ScriptPreference): List<WordInfo> {
        if (lang == Lang.EN) return listOf(enrich(db, Lang.EN, c.enWord, null, c, script))
        val rows = db.rawQuery(
            "SELECT word, gender, roman FROM translation WHERE concept_id = ? AND lang = ? ORDER BY ord LIMIT 4",
            arrayOf(c.id.toString(), lang.code),
        ).use { cur ->
            buildList { while (cur.moveToNext()) add(Triple(cur.getString(0), cur.getStringOrNull(1), cur.getStringOrNull(2))) }
        }
        if (rows.isEmpty() && lang == Lang.ZH) {
            return zhByEnglish(db, c.enWord).map { enrich(db, lang, it, null, c, script).copy(source = "CC-CEDICT") }
        }
        if (rows.isEmpty()) {
            // No translation linked to this meaning: words whose own definition is this English word.
            return wordsByDefinition(db, lang, c.enWord, c.pos).map { m ->
                enrich(db, lang, m.word, m.gender, c, script).copy(gloss = m.gloss, source = "Wiktionary")
            }
        }
        return rows.map { (word, gender, roman) -> enrich(db, lang, word, gender, c, script, roman) }
    }

    /** Entries in [lang] whose English definition is (or lists) [en], best first. See [GlossMatch]. */
    private fun wordsByDefinition(db: SQLiteDatabase, lang: Lang, en: String, pos: String?): List<GlossMatch.Candidate> {
        val w = en.trim().lowercase()
        if (w.length < 3 || w.any { it == '%' || it == '_' }) return emptyList()
        val rows = db.rawQuery(
            "SELECT e.word, e.pos, e.gender, s.gloss FROM entry e JOIN sense s ON s.entry_id = e.id " +
                "WHERE e.lang = ? AND s.form_of IS NULL AND lower(s.gloss) LIKE ? LIMIT 300",
            arrayOf(lang.code, "%$w%"),
        ).use { cur ->
            buildList { while (cur.moveToNext()) add(GlossMatch.Candidate(cur.getString(0), cur.getStringOrNull(1), cur.getStringOrNull(2), cur.getString(3))) }
        }
        return GlossMatch.rank(rows, w, pos)
    }

    private fun headwordInfo(db: SQLiteDatabase, lang: Lang, q: String, c: Concept?, script: ScriptPreference): WordInfo? {
        if (q.isBlank()) return null
        if (lang == Lang.ZH) {
            val simp = zhWords(db, q).firstOrNull() ?: return null
            return enrich(db, lang, simp, null, c, script)
        }
        if (c == null && !hasEntry(db, lang, q)) return null
        return enrich(db, lang, q, null, c, script)
    }

    private fun hasEntry(db: SQLiteDatabase, lang: Lang, q: String): Boolean =
        db.rawQuery("SELECT 1 FROM entry WHERE lang = ? AND norm = ? LIMIT 1", arrayOf(lang.code, TextNorm.normalize(q)))
            .use { it.moveToFirst() }

    /** Adds article, gender, plural, IPA, an example and (for Mandarin) pinyin, traditional and measure word. */
    private fun enrich(
        db: SQLiteDatabase,
        lang: Lang,
        word: String,
        trGender: String?,
        c: Concept?,
        script: ScriptPreference,
        roman: String? = null,
    ): WordInfo {
        if (lang == Lang.ZH) {
            val z = zhReadings(db, word).firstOrNull()
            val simp = z?.simplified ?: word
            val trad = z?.traditional ?: word
            val (main, other) = when (script) {
                ScriptPreference.SIMPLIFIED_FIRST -> simp to trad.takeIf { it != simp }
                ScriptPreference.TRADITIONAL_FIRST -> trad to simp.takeIf { it != trad }
                ScriptPreference.SIMPLIFIED_ONLY -> simp to null
                ScriptPreference.TRADITIONAL_ONLY -> trad to null
            }
            return WordInfo(
                lang = lang, word = main, traditional = other, pinyin = z?.pinyin ?: roman,
                measure = z?.measure, hsk = z?.hsk, gloss = z?.definitions?.take(3)?.joinToString("; "),
            )
        }
        val e = db.rawQuery(
            "SELECT id, word, pos, gender, ipa, plural FROM entry WHERE lang = ? AND norm = ? " +
                "ORDER BY (word = ?) DESC, (pos = ?) DESC, id LIMIT 1",
            arrayOf(lang.code, TextNorm.normalize(word), word, c?.pos ?: ""),
        ).use { cur ->
            if (cur.moveToFirst()) EntryRow(
                cur.getLong(0), cur.getString(1), cur.getStringOrNull(2), cur.getStringOrNull(3),
                cur.getStringOrNull(4), cur.getStringOrNull(5),
            ) else null
        }
        // The selected meaning's part of speech wins ("clé" as an adjective takes no article).
        val pos = c?.pos ?: e?.pos
        val gender = trGender ?: e?.let { en -> en.gender?.takeIf { pos == null || pos == en.pos } }
        val displayWord = e?.word ?: word
        val sense = e?.let { pickSense(db, it.id, c?.enWord) }
        val genitive = if (lang == Lang.DE && e != null) {
            db.rawQuery(
                "SELECT form FROM form WHERE entry_id = ? AND tags LIKE '%genitive%' AND tags LIKE '%singular%' " +
                    "AND tags NOT LIKE '%definite%' LIMIT 1",
                arrayOf(e.id.toString()),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        } else null
        return WordInfo(
            lang = lang, word = displayWord, article = Articles.forNoun(lang, displayWord, gender, pos),
            gender = gender, pos = pos, plural = e?.plural, genitive = genitive, ipa = e?.ipa,
            gloss = sense?.gloss ?: c?.label?.takeIf { lang == Lang.EN && it.isNotBlank() },
            example = sense?.example, exampleEn = sense?.exampleEn,
        )
    }

    private data class EntryRow(
        val id: Long, val word: String, val pos: String?, val gender: String?, val ipa: String?, val plural: String?,
    )

    /** The sense whose English gloss mentions the concept's English word, else the first one with an example. */
    private fun pickSense(db: SQLiteDatabase, entryId: Long, enWord: String?): SenseDetail? {
        val senses = db.rawQuery(
            "SELECT gloss, example, example_en FROM sense WHERE entry_id = ? AND form_of IS NULL ORDER BY ord",
            arrayOf(entryId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(SenseDetail(c.getString(0), c.getStringOrNull(1), c.getStringOrNull(2))) } }
        if (senses.isEmpty()) return null
        val needle = enWord?.lowercase()
        val match = needle?.let { n -> senses.firstOrNull { Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(it.gloss.lowercase()) } }
        val chosen = match ?: senses.first()
        return if (chosen.example == null) chosen.copy(
            example = senses.firstOrNull { it.example != null }?.example?.takeIf { match == null },
        ) else chosen
    }

    /** Readings of a Chinese word, everyday one first (see [ZhRanking.readings]), with HSK levels. */
    private fun zhReadings(db: SQLiteDatabase, word: String): List<ZhReading> {
        val rows = zhRows(db, word)
        if (rows.isEmpty()) return rows
        val links = db.rawQuery(
            "SELECT roman, COUNT(*) FROM translation WHERE lang = 'zh' AND norm = ? AND roman IS NOT NULL GROUP BY roman",
            arrayOf(word),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }
        return ZhRanking.readings(word, rows, links, zhLex.mainReading(word)).take(6)
            .map { r -> if (r.hsk != null) r else r.copy(hsk = zhLex.hsk(r.simplified)) }
    }

    private fun zhRows(db: SQLiteDatabase, word: String): List<ZhReading> =
        db.rawQuery(
            "SELECT simp, trad, pinyin, defs, measure, hsk FROM zh WHERE simp = ?1 OR trad = ?1 " +
                "ORDER BY (simp = ?1) DESC, id LIMIT 20",
            arrayOf(word),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ZhReading(
                        simplified = c.getString(0), traditional = c.getString(1), pinyin = c.getString(2),
                        definitions = (c.getStringOrNull(3) ?: "").split(" / ").filter { it.isNotBlank() },
                        measure = c.getStringOrNull(4), hsk = if (c.isNull(5)) null else c.getInt(5),
                    ),
                )
            }
        }

    /**
     * Pinyin (tone marks) for a Chinese sentence, by greedy longest-match segmentation over CC-CEDICT.
     * Characters not in the dictionary are skipped. Used to check answers typed in pinyin.
     */
    suspend fun pinyinOf(text: String): String = withContext(Dispatchers.IO) {
        val db = database.get()
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (!TextNorm.containsHan(text[i].toString())) { i++; continue }
            var matched = false
            for (len in minOf(4, text.length - i) downTo 1) {
                val piece = text.substring(i, i + len)
                val p = db.rawQuery("SELECT pinyin FROM zh WHERE simp = ?1 OR trad = ?1 ORDER BY hsk IS NULL, id LIMIT 1", arrayOf(piece))
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                if (p != null) { out += p; i += len; matched = true; break }
            }
            if (!matched) i++
        }
        out.joinToString(" ")
    }

    /** A word of Chinese text with its pinyin and traditional form (for the Reader). */
    data class ZhToken(val text: String, val pinyin: String?, val traditional: String?)

    /**
     * Splits Chinese text into dictionary words by greedy longest match (up to 4 characters) over CC-CEDICT,
     * with pinyin and traditional characters per word. Punctuation and unknown characters become their own tokens.
     */
    suspend fun segmentZh(text: String): List<ZhToken> = withContext(Dispatchers.IO) {
        val db = database.get()
        val out = ArrayList<ZhToken>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (!TextNorm.containsHan(ch.toString())) { out += ZhToken(ch.toString(), null, null); i++; continue }
            var found: ZhToken? = null
            for (len in minOf(4, text.length - i) downTo 1) {
                val piece = text.substring(i, i + len)
                if (!TextNorm.containsHan(piece.last().toString())) continue
                val row = db.rawQuery(
                    "SELECT pinyin, trad FROM zh WHERE simp = ?1 OR trad = ?1 ORDER BY (simp = ?1) DESC, hsk IS NULL, id LIMIT 1",
                    arrayOf(piece),
                ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
                if (row != null) { found = ZhToken(piece, row.first, row.second); break }
            }
            val t = found ?: ZhToken(ch.toString(), null, null)
            out += t
            i += t.text.length
        }
        out
    }

    /** Character-by-character traditional → simplified conversion using CC-CEDICT's single-character entries. */
    suspend fun toSimplified(text: String): String = withContext(Dispatchers.IO) {
        val db = database.get()
        buildString {
            for (ch in text) {
                val s = ch.toString()
                if (!TextNorm.containsHan(s)) { append(ch); continue }
                val simp = db.rawQuery("SELECT simp FROM zh WHERE trad = ? AND length(simp) = 1 LIMIT 1", arrayOf(s))
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                append(simp ?: s)
            }
        }
    }

    /**
     * Character-by-character simplified → traditional conversion. Characters that are already traditional are kept;
     * where one simplified character has several traditional forms (发 → 發/髮) the most common entry wins.
     */
    suspend fun toTraditional(text: String): String = withContext(Dispatchers.IO) {
        val db = database.get()
        buildString {
            for (ch in text) {
                val s = ch.toString()
                if (!TextNorm.containsHan(s)) { append(ch); continue }
                val isTrad = db.rawQuery("SELECT 1 FROM zh WHERE trad = ? AND simp = trad LIMIT 1", arrayOf(s)).use { it.moveToFirst() }
                val trad = if (isTrad) null else db.rawQuery("SELECT trad FROM zh WHERE simp = ? AND length(trad) = 1 LIMIT 1", arrayOf(s))
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                append(trad ?: s)
            }
        }
    }

    /** Mandarin text in the script chosen in Settings (Tatoeba and Gemini mix both). */
    suspend fun toScript(text: String, script: ScriptPreference): String = runCatching {
        when (script) {
            ScriptPreference.SIMPLIFIED_FIRST, ScriptPreference.SIMPLIFIED_ONLY -> toSimplified(text)
            ScriptPreference.TRADITIONAL_FIRST, ScriptPreference.TRADITIONAL_ONLY -> toTraditional(text)
        }
    }.getOrDefault(text)

    // ------------------------------------------------------------------ detail view

    suspend fun detail(lang: Lang, word: String): DetailResult = withContext(Dispatchers.IO) {
        val db = database.get()
        val zh = if (lang == Lang.ZH) zhReadings(db, word) else emptyList()
        val hasExtra = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'extra'", null).use { it.moveToFirst() }
        val entries = if (lang == Lang.ZH) emptyList() else db.rawQuery(
            "SELECT id, word, pos, gender, ipa, plural FROM entry WHERE lang = ? AND norm = ? ORDER BY (word = ?) DESC, id LIMIT 6",
            arrayOf(lang.code, TextNorm.normalize(word), word),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(EntryRow(c.getLong(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getStringOrNull(5)))
            }
        }.map { e ->
            val senses = db.rawQuery(
                "SELECT gloss, example, example_en FROM sense WHERE entry_id = ? ORDER BY ord",
                arrayOf(e.id.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(SenseDetail(c.getString(0), c.getStringOrNull(1), c.getStringOrNull(2))) } }
            val forms = db.rawQuery("SELECT form, IFNULL(tags,'') FROM form WHERE entry_id = ?", arrayOf(e.id.toString()))
                .use { c -> buildList { while (c.moveToNext()) add(FormRow(c.getString(0), c.getString(1))) } }
            val extra = if (!hasExtra) null else db.rawQuery("SELECT etymology, synonyms FROM extra WHERE entry_id = ?", arrayOf(e.id.toString()))
                .use { c -> if (c.moveToFirst()) c.getStringOrNull(0) to c.getStringOrNull(1) else null }
            EntryDetail(lang, e.word, e.pos, e.gender, e.ipa, e.plural, senses, forms, extra?.first, WordExtras.split(extra?.second))
        }
        val concepts = when (lang) {
            Lang.EN -> conceptsByEnglish(db, TextNorm.normalize(word))
            Lang.ZH -> zh.map { it.simplified }.distinct().flatMap { conceptsByTranslation(db, Lang.ZH, it, byWord = true) }
            else -> conceptsByTranslation(db, lang, TextNorm.normalize(word), byWord = false)
        }
        DetailResult(lang, word, entries, zh, concepts.distinctBy { it.id })
    }
}

internal fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
