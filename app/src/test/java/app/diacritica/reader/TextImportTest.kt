package app.diacritica.reader

import app.diacritica.data.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TextImportTest {
    @Test fun splitsSentencesAndLines() {
        val s = TextImport.sentences("Der alte Fischer ging zum Meer. Er war müde!\nKapitel 2\n\nWarum? Niemand weiß es.", Lang.DE)
        assertEquals(listOf("Der alte Fischer ging zum Meer.", "Er war müde!", "Kapitel 2", "Warum?", "Niemand weiß es."), s)
    }

    @Test fun splitsChinese() {
        assertEquals(listOf("我很好。", "你呢？"), TextImport.sentences("我很好。你呢？", Lang.ZH))
    }

    @Test fun shortFragmentsJoinThePreviousSentence() {
        val s = TextImport.sentences("Il est parti. ... Puis il est revenu.", Lang.FR)
        assertEquals("Il est parti. ...", s[0])
    }

    @Test fun titleFromFirstSentence() {
        assertEquals("Der alte Fischer ging zum Meer", TextImport.titleFrom(listOf("Der alte Fischer ging zum Meer.")))
        assertEquals("One two three four five six…", TextImport.titleFrom(listOf("One two three four five six seven eight.")))
    }

    @Test fun parsesAlignedTranslations() {
        val out = Json.parseToJsonElement("""{"sentences": [{"i": 2, "en": "He was tired!", "es": "¡Estaba cansado!"}, {"i": 1, "en": "The old fisherman went to the sea.", "es": ""}]}""") as JsonObject
        val rows = TextImport.parse(out, Lang.DE, listOf("Der alte Fischer ging zum Meer.", "Er war müde!", "Ende."), listOf(Lang.EN, Lang.ES))
        assertEquals(3, rows.size)
        assertEquals("The old fisherman went to the sea.", rows[0][Lang.EN])
        assertEquals(null, rows[0][Lang.ES]) // empty → left for on-device translation
        assertEquals("¡Estaba cansado!", rows[1][Lang.ES])
        assertEquals(mapOf(Lang.DE to "Ende."), rows[2])
    }
}
