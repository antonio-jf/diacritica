package app.diacritica.tests

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestGeneratorTest {
    private val req = TestRequest("B2", 4, SentenceLength.LONG, 6, focus = "subjunctive", topic = "work")

    @Test fun promptCarriesTheRequest() {
        val p = TestGenerator.prompt(req)
        assertTrue(p.contains("6 sentences"))
        assertTrue(p.contains("B2 (CEFR)"))
        assertTrue(p.contains("HSK 4"))
        assertTrue(p.contains("15–25 words"))
        assertTrue(p.contains("subjunctive"))
        assertTrue(p.contains("Theme: work."))
    }

    @Test fun parsesAndCleans() {
        val out = Json.parseToJsonElement(
            """{"title": "Wishes at work", "description": "Subjunctive after wishes.", "items": [
                {"en": "I want you to call the client.", "es": "Quiero que llames al cliente.", "hint": "subjunctive after querer"},
                {"en": "I want you to call the client.", "es": "dup"},
                {"en": "It's important that she arrives early.", "es": "Es importante que llegue pronto.", "hint": ""},
                {"en": "Although he was tired, he finished the report.", "es": "Aunque estaba cansado, terminó el informe."},
                {"es": "no english"}
            ]}""",
        ) as JsonObject
        val t = TestGenerator.parse(out, req, 42)!!
        assertEquals("gen-42", t.id)
        assertEquals("Wishes at work", t.title)
        assertEquals(3, t.items.size)
        assertNull(t.items[1].hint)
        assertEquals("subjunctive", t.focus)
        assertTrue(t.description.contains("long sentences"))
        assertNull(TestGenerator.parse(Json.parseToJsonElement("""{"title": "x", "description": "y", "items": []}""") as JsonObject, req, 1))
    }
}
