package app.diacritica.progress

import app.diacritica.data.Lang
import app.diacritica.rehearse.GradeError
import app.diacritica.rehearse.LangFeedback
import app.diacritica.rehearse.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ProgressStatsTest {
    private val today = LocalDate.of(2026, 11, 15)

    @Test fun streakCountsBackFromTodayOrYesterday() {
        val days = setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(5))
        assertEquals(Streaks(3, 3, 4), ProgressStats.streaks(days, today))
        // Nothing yet today: yesterday's streak still counts.
        assertEquals(2, ProgressStats.streaks(setOf(today.minusDays(1), today.minusDays(2)), today).current)
        // Two days ago is too late.
        assertEquals(0, ProgressStats.streaks(setOf(today.minusDays(2)), today).current)
    }

    @Test fun longestStreakAcrossGaps() {
        val days = (0L..6L).map { today.minusDays(20 + it) }.toSet() + setOf(today)
        val s = ProgressStats.streaks(days, today)
        assertEquals(1, s.current)
        assertEquals(7, s.longest)
        assertEquals(Streaks(0, 0, 0), ProgressStats.streaks(emptySet(), today))
    }

    @Test fun accuracyIgnoresUngradedAndBlank() {
        val f = listOf(
            LangFeedback(Lang.FR, "a", Verdict.CORRECT),
            LangFeedback(Lang.FR, "b", Verdict.ACCEPTABLE),
            LangFeedback(Lang.FR, "c", Verdict.INCORRECT),
            LangFeedback(Lang.FR, "d", Verdict.UNGRADED),
            LangFeedback(Lang.DE, "", Verdict.INCORRECT),
        )
        val acc = ProgressStats.accuracy(f, listOf(Lang.EN, Lang.FR, Lang.DE))
        assertEquals(listOf(Lang.FR, Lang.DE), acc.map { it.lang })
        assertEquals(LangAccuracy(Lang.FR, right = 2, checked = 3, answered = 4), acc[0])
        assertEquals(66, acc[0].percent)
        assertNull(acc[1].percent)
    }

    @Test fun errorTypesAreNormalisedAndCountedPerLanguage() {
        fun e(t: String) = GradeError(t, "x", "y", "")
        val f = listOf(
            LangFeedback(Lang.FR, "a", Verdict.INCORRECT, errors = listOf(e("Gender"), e("word-order"))),
            LangFeedback(Lang.DE, "b", Verdict.INCORRECT, errors = listOf(e("gender"), e("Word order"), e("case"))),
        )
        val types = ProgressStats.errorTypes(f)
        assertEquals(listOf("gender", "word order", "case"), types.map { it.type })
        assertEquals(mapOf(Lang.FR to 1, Lang.DE to 1), types[0].byLang)
    }

    @Test fun reviewsAndWords() {
        val r = ProgressStats.reviews(listOf(ReviewEvent("fr", 1, 1), ReviewEvent("fr", 2, 3), ReviewEvent("zh", 3, 4)))
        assertEquals(66, r.recallPercent)
        assertEquals(1 to 2, r.byLang[Lang.FR])
        assertEquals(5, ProgressStats.wordCount("L'homme regarde la mer, enfin.", Lang.FR))
        assertEquals(4, ProgressStats.wordCount("我爱学习。", Lang.ZH))
    }

    @Test fun activityGroupsByDay() {
        val zone = ZoneOffset.UTC
        val t = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val a = ProgressStats.activity(listOf(t, t + 1000, t - 3600_000), zone)
        assertEquals(2, a[today])
        assertEquals(1, a[today.minusDays(1)])
    }
}
