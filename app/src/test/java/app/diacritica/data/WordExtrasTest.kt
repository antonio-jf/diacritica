package app.diacritica.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordExtrasTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun etymologyIsTrimmed() {
        assertNull(WordExtras.etymology(obj("{}")))
        assertEquals("From Latin casa.", WordExtras.etymology(obj("""{"etymology_text": "  From  Latin\n casa. "}""")))
        val long = WordExtras.etymology("First sentence about the word. ".repeat(40))!!
        assertTrue(long.length <= WordExtras.MAX_ETYMOLOGY)
        assertTrue(long.endsWith("."))
    }

    @Test fun synonymsFromEntryAndSenses() {
        val e = obj(
            """{"word": "Haus", "synonyms": [{"word": "Gebäude"}, {"word": "Haus"}, {"word": "Thesaurus:Haus"}],
               "senses": [{"synonyms": [{"word": "Heim"}, {"word": "Gebäude"}]}, {}]}""",
        )
        assertEquals(listOf("Gebäude", "Heim"), WordExtras.synonyms(e))
        assertEquals(emptyList<String>(), WordExtras.synonyms(obj("""{"word": "x"}""")))
    }

    @Test fun storedFormRoundTrips() {
        assertEquals("a|b", WordExtras.join(listOf("a", "b")))
        assertNull(WordExtras.join(emptyList()))
        assertEquals(listOf("a", "b"), WordExtras.split("a| b|"))
        assertEquals(emptyList<String>(), WordExtras.split(null))
    }
}
