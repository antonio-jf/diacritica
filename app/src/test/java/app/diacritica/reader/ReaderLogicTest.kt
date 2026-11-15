package app.diacritica.reader

import app.diacritica.data.Lang
import app.diacritica.ui.reader.wordAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAtTest {
    @Test fun picksTheWordUnderTheFinger() {
        assertEquals("regarde", wordAt("L'homme regarde la mer.", 10, Lang.FR))
        assertEquals("mer", wordAt("L'homme regarde la mer.", 22, Lang.FR)) // on the full stop
    }

    @Test fun dropsFrenchAndItalianElision() {
        assertEquals("homme", wordAt("L'homme regarde la mer.", 3, Lang.FR))
        assertEquals("acqua", wordAt("Un bicchiere dell'acqua.", 16, Lang.IT))
    }

    @Test fun keepsEnglishContractions() {
        assertEquals("don't", wordAt("I don't know.", 3, Lang.EN))
    }

    @Test fun nothingOnPunctuationOrNumbers() {
        assertNull(wordAt("— ¿Qué?", 0, Lang.ES))
        assertNull(wordAt("1999", 1, Lang.EN))
        assertNull(wordAt("", 0, Lang.EN))
    }
}

class OfficialDictionaryTest {
    @Test fun urlsAreEncoded() {
        assertEquals("https://dle.rae.es/ma%C3%B1ana", OfficialDictionary.url(Lang.ES, "Mañana"))
        assertEquals("https://www.treccani.it/vocabolario/citt%C3%A0/", OfficialDictionary.url(Lang.IT, "città"))
        assertTrue(OfficialDictionary.url(Lang.EN, "take off").endsWith("q=take%20off"))
    }

    @Test fun mandarinUsesTraditionalCharacters() {
        val url = OfficialDictionary.url(Lang.ZH, "钥匙", traditional = "鑰匙")
        assertTrue(url.endsWith("word=%E9%91%B0%E5%8C%99"))
    }
}
