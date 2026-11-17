package app.diacritica.grammar

import app.diacritica.data.Lang
import app.diacritica.tests.Lesson
import app.diacritica.tests.LessonExample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonWriterTest {
    private val base = Lesson(
        title = "了 for completed actions", lang = Lang.ZH, why = "Sentences 2 and 5",
        explanation = "Short version.", examples = listOf(LessonExample("我吃了饭。", "I ate.")), practice = listOf("I bought a book."),
    )

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun keyMatchesGrammarGrouping() {
        assertEquals("zh:了 for completed actions", LessonWriter.key(base))
        assertEquals(LessonWriter.key(base), LessonWriter.key(base.copy(title = "了 for completed actions!")))
    }

    @Test fun parsesFullLesson() {
        val out = obj(
            """{"overview":"了 marks completion.","pattern":"verb + 了 + object\nverb + 了 + number + measure word + object",
            "sections":[{"heading":"After the verb","text":"Put 了 right after the verb.","examples":[{"text":"我买了三本书。","translation":"I bought three books.","reading":"Wǒ mǎile sān běn shū.","note":"了 follows 买."}]},
            {"heading":"No 了 with 不","text":"Use 没 instead.","examples":[]}],
            "pitfalls":[{"wrong":"我不去了商店","right":"我没去商店","note":"Negate completed actions with 没."}],
            "contrast":"Spanish uses the preterite.","tip":"Think 'done'.","practice":["a","b","c","d","e","f"]}""",
        )
        val l = LessonWriter.parse(out, base)!!
        assertTrue(l.full)
        assertEquals("了 marks completion.", l.explanation)
        assertEquals(2, l.sections.size)
        assertEquals("Wǒ mǎile sān běn shū.", l.sections[0].examples[0].reading)
        assertEquals(1, l.pitfalls.size)
        assertEquals(6, l.practice.size)
        // Kept from the short lesson
        assertEquals("Sentences 2 and 5", l.why)
        assertEquals(base.examples, l.examples)
    }

    @Test fun noSectionsIsUnusable() {
        assertNull(LessonWriter.parse(obj("""{"overview":"x","sections":[]}"""), base))
    }

    @Test fun promptAsksForPinyinOnlyForMandarin() {
        assertTrue(LessonWriter.prompt(base, "B1", 2).contains("pinyin"))
        assertTrue(!LessonWriter.prompt(base.copy(lang = Lang.DE), "B1", 2).contains("pinyin"))
    }
}
