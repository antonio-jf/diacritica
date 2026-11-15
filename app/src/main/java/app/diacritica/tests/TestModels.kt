package app.diacritica.tests

import app.diacritica.data.Lang
import app.diacritica.rehearse.GradeError
import app.diacritica.rehearse.LangFeedback
import app.diacritica.rehearse.Verdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ------------------------------------------------------------------ bundled tests (assets/tests/tests.json)

@Serializable
data class TestItem(val en: String, val es: String, val hint: String? = null) {
    fun prompt(source: Lang): String = if (source == Lang.ES) es else en
}

@Serializable
data class TestSet(
    val id: String,
    val title: String,
    /** CEFR level for the European languages. */
    val level: String,
    /** Matching HSK level for Mandarin. */
    val hsk: Int,
    /** A GrammarTopics key (or "mixed"). */
    val focus: String,
    val description: String,
    val items: List<TestItem>,
)

@Serializable
private data class TestFile(val format: Int = 1, val tests: List<TestSet>)

object TestBank {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<TestSet> = json.decodeFromString<TestFile>(text).tests

    /** A short follow-up test from a lesson's practice sentences (English; Spanish shows the same text). */
    fun fromLesson(lesson: Lesson, from: TestSet): TestSet = TestSet(
        id = "lesson-" + lesson.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40),
        title = "Practice: " + lesson.title,
        level = from.level, hsk = from.hsk,
        focus = lesson.title,
        description = lesson.why,
        items = lesson.practice.map { TestItem(it, it) },
    )
}

// ------------------------------------------------------------------ attempts and reviews

@Serializable
enum class AttemptStatus { DRAFT, PENDING, REVIEWED }

@Serializable
data class LessonExample(val text: String, val translation: String)

/** A short grammar lesson aimed at a mistake the learner made. */
@Serializable
data class Lesson(
    val title: String,
    val lang: Lang? = null,
    /** Which answers showed the problem, e.g. "Sentences 2 and 5 (French)". */
    val why: String = "",
    val explanation: String,
    val examples: List<LessonExample> = emptyList(),
    /** English sentences to practise the point (they can be sent to Rehearse later). */
    val practice: List<String> = emptyList(),
)

/** The review of one answer: [item] is the sentence index (0-based). */
@Serializable
data class AnswerReview(
    val item: Int,
    val lang: Lang,
    val answer: String,
    /** 0–100. */
    val score: Int,
    val verdict: Verdict,
    val corrected: String? = null,
    val errors: List<GradeError> = emptyList(),
    val comment: String? = null,
)

@Serializable
data class TestReview(
    val answers: List<AnswerReview>,
    val summary: String,
    val lessons: List<Lesson> = emptyList(),
    val model: String? = null,
) {
    /** Overall score, 0–100: the mean of every answer (empty ones count as 0). */
    val score: Int get() = TestScoring.mean(answers.map { it.score })
    fun scoreFor(lang: Lang): Int = TestScoring.mean(answers.filter { it.lang == lang }.map { it.score })
    fun forItem(i: Int): List<AnswerReview> = answers.filter { it.item == i }
}

@Serializable
data class TestAttempt(
    val id: Long,
    val testId: String,
    val title: String,
    /** The language the sentences were shown in (English or Spanish). */
    val source: Lang,
    val langs: List<Lang>,
    val startedAt: Long,
    val submittedAt: Long? = null,
    /** Answers keyed by [key]. */
    val answers: Map<String, String> = emptyMap(),
    val status: AttemptStatus = AttemptStatus.DRAFT,
    val review: TestReview? = null,
    /** Last problem sending it for review (no key, offline…). */
    val error: String? = null,
    /** A practice test made from a lesson: its own sentences instead of a bundled test's. */
    val custom: TestSet? = null,
) {
    fun answer(item: Int, lang: Lang): String = answers[key(item, lang)].orEmpty()
    fun withAnswer(item: Int, lang: Lang, text: String) = copy(answers = answers + (key(item, lang) to text))
    fun answeredCount(items: Int): Int = (0 until items).sumOf { i -> langs.count { answer(i, it).isNotBlank() } }

    companion object {
        fun key(item: Int, lang: Lang) = "$item:${lang.code}"
    }
}

object TestScoring {
    fun mean(scores: List<Int>): Int = if (scores.isEmpty()) 0 else Math.round(scores.sumOf { it.coerceIn(0, 100) }.toDouble() / scores.size).toInt()

    /** Verdict from a score, used when the reviewer's verdict is missing or contradicts the score. */
    fun verdictFor(score: Int): Verdict = when {
        score >= 90 -> Verdict.CORRECT
        score >= 70 -> Verdict.ACCEPTABLE
        else -> Verdict.INCORRECT
    }

    fun label(score: Int): String = when {
        score >= 90 -> "Excellent"
        score >= 75 -> "Good"
        score >= 60 -> "Getting there"
        score >= 40 -> "Needs work"
        else -> "Keep practising"
    }

    /**
     * Fills in answers the reviewer skipped (an empty answer scores 0) and fixes inconsistent
     * verdicts, so every (sentence, language) pair has exactly one review.
     */
    fun complete(attempt: TestAttempt, items: Int, raw: List<AnswerReview>): List<AnswerReview> {
        val byKey = raw.filter { it.item in 0 until items && it.lang in attempt.langs }
            .associateBy { TestAttempt.key(it.item, it.lang) }
        return (0 until items).flatMap { i ->
            attempt.langs.map { l ->
                val answer = attempt.answer(i, l)
                val r = byKey[TestAttempt.key(i, l)]
                when {
                    answer.isBlank() -> AnswerReview(i, l, "", 0, Verdict.INCORRECT, comment = "No answer.")
                    r == null -> AnswerReview(i, l, answer, 0, Verdict.UNGRADED, comment = "The reviewer skipped this answer.")
                    else -> {
                        val s = r.score.coerceIn(0, 100)
                        val v = if (r.verdict == Verdict.UNGRADED) verdictFor(s) else r.verdict
                        r.copy(answer = answer, score = s, verdict = v)
                    }
                }
            }
        }
    }

    /** Test answers in the shape Progress counts (accuracy per language, mistakes by type). */
    fun asFeedback(review: TestReview): List<LangFeedback> = review.answers.filter { it.verdict != Verdict.UNGRADED }.map {
        LangFeedback(it.lang, it.answer, it.verdict, corrected = it.corrected, errors = it.errors, note = it.comment, byAi = true)
    }
}
