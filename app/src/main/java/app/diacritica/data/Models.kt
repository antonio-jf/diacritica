package app.diacritica.data

/** One English Wiktionary sense that has translations; the bridge between all languages. */
data class Concept(
    val id: Long,
    val enWord: String,
    val pos: String?,
    val label: String,
)

/** A word in one language, ready to display. */
data class WordInfo(
    val lang: Lang,
    val word: String,
    val article: String? = null,
    val gender: String? = null,
    val pos: String? = null,
    val plural: String? = null,
    val genitive: String? = null,
    val ipa: String? = null,
    val gloss: String? = null,
    val example: String? = null,
    val exampleEn: String? = null,
    // Mandarin only
    val traditional: String? = null,
    val pinyin: String? = null,
    val measure: String? = null,
    val hsk: Int? = null,
    /** Where the word came from when not from Wiktionary's translation tables (e.g. "CC-CEDICT"). */
    val source: String? = null,
)

/** All words for one language under the selected sense; empty = no translation in the data. */
data class LangResult(val lang: Lang, val words: List<WordInfo>)

/** What an inflected word is: "nahm" → description "first/third-person singular preterite", lemma "nehmen". */
data class FormNote(val lemma: String, val description: String)

data class LookupResult(
    val query: String,
    val source: Lang,
    /** Other languages where the same spelling also matched (e.g. "casa" in IT and ES). */
    val alsoFoundIn: List<Lang>,
    val headword: WordInfo?,
    val concepts: List<Concept>,
    val selected: Int,
    val results: List<LangResult>,
    /** Set when the query was an inflected form, e.g. "chiavi" → "chiave". */
    val redirectedFrom: String? = null,
    /** What the query is a form of, from Wiktionary ("first/third-person singular preterite of nehmen"). */
    val forms: List<FormNote> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** Other Chinese words matching a pinyin/hanzi query (homophones). */
    val zhAlternatives: List<ZhReading> = emptyList(),
) {
    val isEmpty: Boolean get() = concepts.isEmpty() && headword == null
}

data class SenseDetail(val gloss: String, val example: String?, val exampleEn: String?)
data class FormRow(val form: String, val tags: String)

data class EntryDetail(
    val lang: Lang,
    val word: String,
    val pos: String?,
    val gender: String?,
    val ipa: String?,
    val plural: String?,
    val senses: List<SenseDetail>,
    val forms: List<FormRow>,
    /** From Wiktionary; null in dictionaries built before etymologies were included. */
    val etymology: String? = null,
    val synonyms: List<String> = emptyList(),
)

data class ZhReading(
    val simplified: String,
    val traditional: String,
    val pinyin: String,
    val definitions: List<String>,
    val measure: String?,
    val hsk: Int?,
)

data class DetailResult(
    val lang: Lang,
    val word: String,
    val entries: List<EntryDetail>,
    val zh: List<ZhReading>,
    /** Other words for the same meaning, by language (for the "in other languages" strip). */
    val concepts: List<Concept>,
)

data class DictionaryInfo(val label: String, val builtAt: String, val languages: List<Lang>, val sizeBytes: Long)

object FormNoteText {
    /** "first/third-person singular preterite of nehmen" → description "first/third-person singular preterite". */
    fun parse(lemma: String, gloss: String): FormNote {
        val g = gloss.trim().trimEnd('.')
        val cut = Regex("\\s+of\\s+${Regex.escape(lemma)}\\b.*$", RegexOption.IGNORE_CASE)
        val desc = g.replace(cut, "").trim().ifEmpty { "form" }
        return FormNote(lemma, desc)
    }
}
