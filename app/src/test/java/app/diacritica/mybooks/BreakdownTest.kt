package app.diacritica.mybooks

import app.diacritica.data.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakdownTest {
    private val sample = """
        {"translation":"I get up at seven every morning.","literal":"I every day morning seven o'clock get up.",
         "words":[{"text":"我","reading":"wǒ","meaning":"I","role":"subject"},
                  {"text":"起床","reading":"qǐchuáng","lemma":"起床","meaning":"get up","role":"verb"},
                  {"meaning":"no text, dropped"}],
         "grammar":[{"title":"Time before the verb","pattern":"Subject + time + verb","inPassage":"每天早上七点起床",
                     "explanation":"Time expressions come before the verb in Mandarin.",
                     "examples":[{"text":"我八点上班。","translation":"I start work at eight."}],
                     "practice":["I eat lunch at noon.","She sleeps at eleven."]},
                    {"title":"No explanation, dropped"}],
         "vocabulary":[{"word":"起床","reading":"qǐchuáng","meaning":"to get up"},{"word":"x"}]}
    """.trimIndent()

    @Test fun parsesAndDropsUnusableItems() {
        val b = Breakdown.parse(Json.parseToJsonElement(sample).jsonObject)!!
        assertEquals("I get up at seven every morning.", b.translation)
        assertEquals(2, b.words.size)
        assertNull(b.words[1].lemma) // same as the text
        assertEquals(1, b.grammar.size)
        assertEquals(2, b.grammar[0].practice.size)
        assertEquals(1, b.vocabulary.size)
    }

    @Test fun noTranslationMeansNoBreakdown() {
        assertNull(Breakdown.parse(Json.parseToJsonElement("""{"words":[]}""").jsonObject))
    }

    @Test fun roundTripsAsJson() {
        val b = Breakdown.parse(Json.parseToJsonElement(sample).jsonObject)!!
        assertEquals(b, Breakdown.decode(Breakdown.encode(b)))
        assertNull(Breakdown.decode("not json"))
    }

    @Test fun makesALessonFromAPoint() {
        val b = Breakdown.parse(Json.parseToJsonElement(sample).jsonObject)!!
        val l = b.lesson(b.grammar[0], Lang.ZH, "小书", "我每天早上七点起床。")
        assertEquals("Time before the verb", l.title)
        assertEquals(Lang.ZH, l.lang)
        assertTrue(l.why.startsWith("From “小书”"))
        assertTrue(l.explanation.contains("Subject + time + verb"))
        assertTrue(l.explanation.contains("In the passage: 每天早上七点起床"))
        assertEquals(2, l.practice.size)
    }

    @Test fun promptAsksForPinyinOnlyForMandarin() {
        assertTrue(Breakdown.prompt("我起床。", Lang.ZH, "T", null).contains("pinyin"))
        assertTrue(!Breakdown.prompt("Je me lève.", Lang.FR, "T", null).contains("pinyin"))
    }
}
