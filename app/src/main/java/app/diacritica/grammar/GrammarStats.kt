package app.diacritica.grammar

import app.diacritica.data.Lang
import app.diacritica.progress.ProgressStats
import app.diacritica.rehearse.GrammarTopics
import app.diacritica.tests.Lesson

/** One mistake from a Rehearse round or a reviewed test. */
data class Mistake(
    val lang: Lang,
    val type: String,
    val at: Long,
    val wrong: String,
    val right: String,
    val explanation: String,
)

/** A kind of mistake you keep making in one language, with how it's going. */
data class WeakPoint(
    val lang: Lang,
    val type: String,
    /** Mistakes in the last [GrammarStats.WINDOW_DAYS] days, and in the window before. */
    val recent: Int,
    val previous: Int,
    val total: Int,
    /** Newest first, at most 3. */
    val examples: List<Mistake>,
) {
    val label: String get() = GrammarStats.typeLabel(type)
    val trend: Trend get() = when {
        previous == 0 && recent > 0 -> Trend.NEW
        recent > previous -> Trend.UP
        recent < previous -> Trend.DOWN
        else -> Trend.SAME
    }
    /** The Rehearse "Grammar focus" topic that practises this, or "mixed". */
    val topic: String get() = GrammarStats.topicFor(type, lang)
}

enum class Trend { NEW, UP, DOWN, SAME }

/** A lesson from a test review, merged with the same lesson from other tests. */
data class LessonEntry(
    val lesson: Lesson,
    val attemptId: Long,
    val testTitle: String,
    val at: Long,
    /** How many reviews wrote this lesson (same title and language). */
    val times: Int,
)

object GrammarStats {
    const val WINDOW_DAYS = 30
    private const val DAY = 24L * 3600 * 1000

    /** Mistake types that aren't grammar points to work on. */
    private val ignored = setOf("other", "missing word", "extra word")

    /**
     * Weak points, worst first: most mistakes in the last 30 days, then overall. Types that only
     * happened once ever are left out (a slip, not a pattern).
     */
    fun weakPoints(mistakes: List<Mistake>, now: Long, max: Int = 12): List<WeakPoint> {
        val windowStart = now - WINDOW_DAYS * DAY
        val prevStart = now - 2 * WINDOW_DAYS * DAY
        return mistakes
            .map { it.copy(type = ProgressStats.normaliseType(it.type)) }
            .filter { it.type.isNotEmpty() && it.type !in ignored }
            .groupBy { it.lang to it.type }
            .map { (k, list) ->
                WeakPoint(
                    lang = k.first, type = k.second,
                    recent = list.count { it.at >= windowStart },
                    previous = list.count { it.at in prevStart until windowStart },
                    total = list.size,
                    examples = list.sortedByDescending { it.at }.distinctBy { it.wrong.lowercase() to it.right.lowercase() }.take(3),
                )
            }
            .filter { it.total >= 2 }
            .sortedWith(compareByDescending<WeakPoint> { it.recent }.thenByDescending { it.total })
            .take(max)
    }

    /** Lessons newest first; the same title in the same language is shown once, with a count. */
    fun lessons(all: List<LessonEntry>): List<LessonEntry> =
        all.groupBy { (it.lesson.lang?.code ?: "") + ":" + it.lesson.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() }
            .map { (_, same) -> same.maxBy { it.at }.copy(times = same.size) }
            .sortedByDescending { it.at }

    fun typeLabel(type: String): String = when (ProgressStats.normaliseType(type)) {
        "word order" -> "Word order"
        "word choice" -> "Word choice"
        "measure word" -> "Measure words"
        "mood" -> "Mood (subjunctive, conditional)"
        "case" -> "Cases"
        "tones" -> "Tones"
        "aspect" -> "Aspect (了, 过, 着)"
        "prepositions" -> "Prepositions"
        "articles" -> "Articles"
        "agreement" -> "Agreement"
        "conjugation" -> "Conjugation"
        "tense" -> "Tenses"
        "gender" -> "Gender"
        "accents" -> "Accents"
        "spelling" -> "Spelling"
        "register" -> "Register"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** Which Rehearse Grammar focus practises a mistake type in a language. */
    fun topicFor(type: String, lang: Lang): String {
        val key = when (ProgressStats.normaliseType(type)) {
            "mood" -> if (lang == Lang.DE || lang == Lang.EN) "conditional" else "subjunctive"
            "tense" -> "past"
            "case" -> "cases"
            "prepositions" -> if (lang == Lang.DE) "cases" else "mixed"
            "word order" -> if (lang == Lang.DE) "separable" else "mixed"
            "aspect" -> "le"
            "measure word" -> "measure"
            else -> "mixed"
        }
        val topic = GrammarTopics.byKey(key)
        return if (topic.langs.isEmpty() || lang in topic.langs) topic.key else "mixed"
    }

    /** What a practice set should drill, in words, for the AI. */
    fun describe(w: WeakPoint): String {
        val focus = GrammarTopics.byKey(w.topic).takeIf { w.topic != "mixed" }?.instruction
        return buildString {
            append("${w.label.lowercase()} in ${w.lang.englishName}")
            focus?.let { append(" ($it)") }
        }
    }
}
