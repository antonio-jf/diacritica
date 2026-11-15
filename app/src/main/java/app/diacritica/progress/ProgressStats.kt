package app.diacritica.progress

import app.diacritica.data.Lang
import app.diacritica.rehearse.LangFeedback
import app.diacritica.rehearse.Verdict

/** Accuracy for one language in Rehearse: right = correct or acceptable; checked = everything graded. */
data class LangAccuracy(val lang: Lang, val right: Int, val checked: Int, val answered: Int) {
    val percent: Int? get() = if (checked == 0) null else right * 100 / checked
}

/** How often one kind of mistake appeared, overall and per language. */
data class ErrorCount(val type: String, val count: Int, val byLang: Map<Lang, Int>)

/** A Review rating with its card's language (1 Again … 4 Easy). */
data class ReviewEvent(val lang: String, val at: Long, val rating: Int)

data class ReviewSummary(val reviews: Int, val recalled: Int, val byLang: Map<Lang, Pair<Int, Int>>) {
    val recallPercent: Int? get() = if (reviews == 0) null else recalled * 100 / reviews
}

/** Pure calculations behind the Progress page (no Android, so they are unit-tested). */
object ProgressStats {

    /** Per-language Rehearse accuracy, in the order of [langs]. */
    fun accuracy(feedback: List<LangFeedback>, langs: List<Lang>): List<LangAccuracy> {
        val by = feedback.groupBy { it.lang }
        return langs.mapNotNull { l ->
            val fs = by[l] ?: return@mapNotNull null
            val answered = fs.filter { it.answer.isNotBlank() }
            val checked = answered.filter { it.verdict != Verdict.UNGRADED }
            val right = checked.count { it.verdict == Verdict.CORRECT || it.verdict == Verdict.ACCEPTABLE }
            LangAccuracy(l, right, checked.size, answered.size)
        }
    }

    /** Error types, most frequent first. Types are normalised ("Word order" = "word-order" = "word order"). */
    fun errorTypes(feedback: List<LangFeedback>): List<ErrorCount> {
        val counts = HashMap<String, HashMap<Lang, Int>>()
        for (f in feedback) for (e in f.errors) {
            val t = normaliseType(e.type)
            if (t.isEmpty()) continue
            val m = counts.getOrPut(t) { HashMap() }
            m[f.lang] = (m[f.lang] ?: 0) + 1
        }
        return counts.map { (t, m) -> ErrorCount(t, m.values.sum(), m) }.sortedWith(compareByDescending<ErrorCount> { it.count }.thenBy { it.type })
    }

    fun normaliseType(t: String): String = t.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")

    /** Review: a rating above Again counts as recalled. */
    fun reviews(events: List<ReviewEvent>): ReviewSummary {
        val byLang = events.groupBy { Lang.fromCode(it.lang) }.filterKeys { it != null }
            .map { (l, es) -> l!! to (es.count { it.rating > 1 } to es.size) }.toMap()
        return ReviewSummary(events.size, events.count { it.rating > 1 }, byLang)
    }

    /** Words in [text]; Mandarin counts characters (no spaces between words). */
    fun wordCount(text: String, lang: Lang): Int =
        if (lang == Lang.ZH) text.count { it in '一'..'鿿' }
        else Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*").findAll(text).count()

}
