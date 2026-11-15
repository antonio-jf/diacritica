package app.diacritica.progress

import app.diacritica.data.Lang
import app.diacritica.rehearse.GradeError
import app.diacritica.rehearse.LangFeedback
import app.diacritica.rehearse.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressStatsTest {
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

}
