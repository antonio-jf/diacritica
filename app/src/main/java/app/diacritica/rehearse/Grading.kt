package app.diacritica.rehearse

import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
enum class Verdict { CORRECT, ACCEPTABLE, INCORRECT, UNGRADED }

@Serializable
data class GradeError(val type: String, val wrong: String, val right: String, val explanation: String)

/** Feedback for one language in one round. Serializable so rounds can be archived. */
@Serializable
data class LangFeedback(
    val lang: Lang,
    val answer: String,
    val verdict: Verdict,
    /** The learner's answer with minimal corrections (used for the diff). */
    val corrected: String? = null,
    val errors: List<GradeError> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val note: String? = null,
    /** Reference translation shown for comparison. */
    val reference: String? = null,
    val referenceGenerated: Boolean = false,
    /** True when the AI graded it; false = offline rule check only. */
    val byAi: Boolean = false,
)

/** Offline checks: exact match, accent-only and tone-only differences, near misses. */
object RuleChecker {
    private val punct = Regex("[\\p{P}\\p{S}&&[^']]")
    private val spaces = Regex("\\s+")
    private val marks = Regex("\\p{Mn}+")

    /** Lowercase, unify apostrophes, drop punctuation and extra spaces; keeps accents. */
    fun loose(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase().replace('’', '\'').replace(punct, " ").replace(spaces, " ").trim()

    private fun noAccents(s: String) = marks.replace(Normalizer.normalize(loose(s), Normalizer.Form.NFD), "")

    fun check(
        lang: Lang,
        answer: String,
        references: List<String>,
        strictAccents: Boolean,
        strictTones: Boolean,
        referencePinyin: List<String> = emptyList(),
    ): LangFeedback {
        val ref = references.firstOrNull()
        if (answer.isBlank()) return LangFeedback(lang, answer, Verdict.INCORRECT, note = "No answer.", reference = ref)
        if (references.isEmpty()) return LangFeedback(lang, answer, Verdict.UNGRADED, note = "No reference translation available offline.")

        // Mandarin typed as pinyin: compare against the reference's pinyin.
        if (lang == Lang.ZH && !TextNorm.containsHan(answer) && referencePinyin.isNotEmpty()) {
            val a = pinyinKey(answer, keepTones = true)
            referencePinyin.forEachIndexed { i, rp ->
                if (a == pinyinKey(rp, keepTones = true)) return LangFeedback(lang, answer, Verdict.CORRECT, reference = references.getOrNull(i) ?: ref)
                if (pinyinKey(answer, false) == pinyinKey(rp, false)) {
                    return LangFeedback(
                        lang, answer, if (strictTones) Verdict.INCORRECT else Verdict.ACCEPTABLE, corrected = rp,
                        errors = listOf(GradeError("tones", answer, rp, "The syllables are right but one or more tones are missing or wrong. Correct pinyin: $rp")),
                        reference = references.getOrNull(i) ?: ref,
                    )
                }
            }
            return LangFeedback(lang, answer, Verdict.UNGRADED, reference = ref, note = "Pinyin differs from the reference; an AI check can tell whether it is still correct.")
        }

        val a = loose(answer)
        references.forEach { r -> if (a == loose(r)) return LangFeedback(lang, answer, Verdict.CORRECT, reference = r) }
        references.forEach { r ->
            if (noAccents(answer) == noAccents(r)) {
                val diffs = WordDiff.changedPairs(answer, r)
                return LangFeedback(
                    lang, answer, if (strictAccents) Verdict.INCORRECT else Verdict.ACCEPTABLE, corrected = r,
                    errors = diffs.map { (w, c) -> GradeError("accents", w, c, "Accent or special letter: “$w” is written “$c”.") },
                    reference = r,
                )
            }
        }
        // Near miss: show the closest reference; the AI explains why.
        val closest = references.minBy { WordDiff.distance(a, loose(it)) }
        return LangFeedback(lang, answer, Verdict.UNGRADED, corrected = null, reference = closest,
            note = "Different from the reference translation. That can still be correct; an AI check explains the difference.")
    }

    private fun pinyinKey(s: String, keepTones: Boolean): String {
        val base = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace("'", "").replace(punct, "").replace(spaces, "")
        return if (keepTones) Normalizer.normalize(base, Normalizer.Form.NFC) else marks.replace(base, "").replace(Regex("[1-5]"), "")
    }
}

/** Word-level diff (LCS) between the learner's answer and the corrected version. */
object WordDiff {
    enum class Op { SAME, REMOVED, ADDED }
    data class Segment(val op: Op, val text: String)

    private fun tokens(s: String): List<String> =
        if (TextNorm.containsHan(s)) s.map { it.toString() }.filter { it.isNotBlank() } else s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun key(t: String) = RuleChecker.loose(t)

    fun diff(answer: String, corrected: String): List<Segment> {
        val a = tokens(answer); val b = tokens(corrected)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (key(a[i]) == key(b[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Segment>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                key(a[i]) == key(b[j]) -> { out += Segment(Op.SAME, b[j]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += Segment(Op.REMOVED, a[i]); i++ }
                else -> { out += Segment(Op.ADDED, b[j]); j++ }
            }
        }
        while (i < a.size) out += Segment(Op.REMOVED, a[i++])
        while (j < b.size) out += Segment(Op.ADDED, b[j++])
        return out
    }

    /** Pairs of (learner word, corrected word) that differ, for accent-only errors. */
    fun changedPairs(answer: String, corrected: String): List<Pair<String, String>> {
        val a = tokens(answer); val b = tokens(corrected)
        if (a.size != b.size) return listOf(answer to corrected)
        return a.zip(b).filter { (x, y) -> x != y && RuleChecker.loose(x) != RuleChecker.loose(y) }
    }

    fun distance(x: String, y: String): Int {
        val a = x.split(' '); val b = y.split(' ')
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.size][b.size]
    }
}
