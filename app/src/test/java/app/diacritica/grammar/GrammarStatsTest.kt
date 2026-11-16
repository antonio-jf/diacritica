package app.diacritica.grammar

import app.diacritica.data.Lang
import app.diacritica.tests.Lesson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarStatsTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_000 * day
    private fun m(lang: Lang, type: String, daysAgo: Int, wrong: String = "x$daysAgo") =
        Mistake(lang, type, now - daysAgo * day, wrong, "y", "because")

    @Test fun weakPointsRankAndTrend() {
        val ms = listOf(
            m(Lang.DE, "case", 1), m(Lang.DE, "Case", 3), m(Lang.DE, "case", 10), m(Lang.DE, "case", 40),
            m(Lang.FR, "gender", 2), m(Lang.FR, "gender", 50), m(Lang.FR, "gender", 55),
            m(Lang.IT, "word-order", 5), m(Lang.IT, "word_order", 6),
            m(Lang.ES, "spelling", 1), // once: a slip, not a pattern
            m(Lang.DE, "other", 1), m(Lang.DE, "other", 2), // not a grammar point
        )
        val w = GrammarStats.weakPoints(ms, now)
        assertEquals(listOf(Lang.DE to "case", Lang.IT to "word order", Lang.FR to "gender"), w.map { it.lang to it.type })
        val de = w[0]
        assertEquals(3, de.recent); assertEquals(1, de.previous); assertEquals(4, de.total)
        assertEquals(Trend.UP, de.trend)
        assertEquals("x1", de.examples.first().wrong)
        assertEquals(Trend.NEW, w[1].trend)
        assertEquals(Trend.DOWN, w[2].trend)
    }

    @Test fun topics() {
        assertEquals("cases", GrammarStats.topicFor("case", Lang.DE))
        assertEquals("subjunctive", GrammarStats.topicFor("mood", Lang.FR))
        assertEquals("conditional", GrammarStats.topicFor("mood", Lang.DE))
        assertEquals("separable", GrammarStats.topicFor("word-order", Lang.DE))
        assertEquals("mixed", GrammarStats.topicFor("word order", Lang.FR))
        assertEquals("le", GrammarStats.topicFor("aspect", Lang.ZH))
        assertEquals("measure", GrammarStats.topicFor("measure-word", Lang.ZH))
        // German cases make no sense for Italian.
        assertEquals("mixed", GrammarStats.topicFor("case", Lang.IT))
        assertEquals("Word order", GrammarStats.typeLabel("word-order"))
    }

    @Test fun lessonsMergeRepeats() {
        fun e(title: String, lang: Lang?, at: Long, id: Long) = LessonEntry(Lesson(title, lang, explanation = "…"), id, "Test $id", at, 1)
        val out = GrammarStats.lessons(listOf(
            e("Dative after mit", Lang.DE, 1, 1), e("Dative after MIT!", Lang.DE, 5, 2), e("Dative after mit", Lang.FR, 3, 3), e("Gender", Lang.IT, 4, 4),
        ))
        assertEquals(3, out.size)
        assertEquals(2L, out.first().attemptId)
        assertEquals(2, out.first().times)
    }

    @Test fun practiceParse() {
        val w = WeakPoint(Lang.DE, "case", 3, 1, 4, listOf(m(Lang.DE, "case", 1, "mit den Freund")))
        val out = Json.parseToJsonElement("""{"title": "German dative", "sentences": ["I'm going with my friend.", "She helps her mother.", "", "I'm going with my friend.", "He gives the dog a bone."]}""") as JsonObject
        val t = GrammarPractice.parse(out, w, "B1", 3, now)!!
        assertEquals("Practice: German dative", t.title)
        assertEquals(3, t.items.size)
        assertTrue(t.focus.startsWith("cases in German"))
        assertNull(GrammarPractice.parse(Json.parseToJsonElement("""{"title": "x", "sentences": ["one"]}""") as JsonObject, w, "B1", 3, now))
        assertTrue(GrammarPractice.prompt(w, "B1", 3).contains("\"mit den Freund\""))
    }
}
