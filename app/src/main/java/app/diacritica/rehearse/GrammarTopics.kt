package app.diacritica.rehearse

import app.diacritica.data.Lang

/** A structure for the Grammar focus path. [langs] = the languages where it matters (empty = all). */
data class GrammarTopic(val key: String, val label: String, val langs: Set<Lang>, val instruction: String)

object GrammarTopics {
    private val ROMANCE = setOf(Lang.FR, Lang.IT, Lang.ES)

    val all: List<GrammarTopic> = listOf(
        GrammarTopic("mixed", "Mixed", emptySet(), "any structure that learners at this level often get wrong in these languages"),
        GrammarTopic("subjunctive", "Subjunctive", ROMANCE,
            "the present or past subjunctive after wishes, doubts, emotions or conjunctions such as bien que / benché / aunque"),
        GrammarTopic("past", "Past tenses", ROMANCE + Lang.DE,
            "choosing between perfect and imperfect past tenses (passé composé/imparfait, passato prossimo/imperfetto, pretérito/imperfecto, Perfekt/Präteritum)"),
        GrammarTopic("pronouns", "Object pronouns", ROMANCE,
            "object and adverbial pronouns and their order (y/en, ci/ne, se lo / glielo, enclitics on infinitives and imperatives)"),
        GrammarTopic("conditional", "If-clauses", ROMANCE + Lang.DE + Lang.EN,
            "hypothetical if-clauses (si + imparfait/conditionnel, se + congiuntivo/condizionale, si + subjuntivo, wenn + Konjunktiv II)"),
        GrammarTopic("cases", "German cases", setOf(Lang.DE),
            "German cases after prepositions and verbs (two-way prepositions, dative verbs, genitive), with articles and adjective endings"),
        GrammarTopic("separable", "German word order", setOf(Lang.DE),
            "German word order: separable verbs, verb-second, verb-final in subordinate clauses"),
        GrammarTopic("serestar", "Ser / estar, por / para", setOf(Lang.ES),
            "Spanish ser vs estar and por vs para"),
        GrammarTopic("le", "Mandarin 了 and 过", setOf(Lang.ZH),
            "Mandarin aspect particles 了, 过 and 着"),
        GrammarTopic("ba", "Mandarin 把 and 被", setOf(Lang.ZH),
            "the Mandarin 把 and 被 constructions"),
        GrammarTopic("measure", "Mandarin measure words", setOf(Lang.ZH),
            "Mandarin measure words (个, 本, 张, 只, 条, 件, 杯…)"),
        GrammarTopic("relative", "Relative clauses", emptySet(),
            "relative clauses (dont / cui / cuyo / dessen / 的 before the noun)"),
        GrammarTopic("comparison", "Comparisons", emptySet(),
            "comparisons and superlatives (plus… que, più… di, más… que, als / wie, 比 / 没有… 那么)"),
    )

    fun byKey(key: String): GrammarTopic = all.firstOrNull { it.key == key } ?: all.first()

    /** Topics that matter for at least one of [langs]. */
    fun forLanguages(langs: Collection<Lang>): List<GrammarTopic> = all.filter { t -> t.langs.isEmpty() || t.langs.any { it in langs } }
}
