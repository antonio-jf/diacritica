package app.diacritica.reader

import app.diacritica.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordMatchTest {
    @Test fun exactWordIgnoringCaseAndAccents() {
        assertEquals("pescador", WordMatch.find("El viejo pescador abrió la puerta.", Lang.ES, listOf("pescador")))
        assertEquals("Tür", WordMatch.find("Er öffnete die Tür seiner Hütte.", Lang.DE, listOf("Tur")))
    }

    @Test fun inflectedFormsByStem() {
        assertEquals("pêcheurs", WordMatch.find("Les pêcheurs partent tôt.", Lang.FR, listOf("pêcheur")))
        assertEquals("regardait", WordMatch.find("Il regardait la mer.", Lang.FR, listOf("regarder")))
    }

    @Test fun elisionAndPhrases() {
        assertEquals("homme", WordMatch.find("L'homme regarde la mer.", Lang.FR, listOf("homme")))
        assertEquals("salle à manger", WordMatch.find("Ils dînent dans la salle à manger.", Lang.FR, listOf("salle à manger", "salle")))
    }

    @Test fun mandarinLongestSubstring() {
        assertEquals("渔夫", WordMatch.find("老渔夫打开了门。", Lang.ZH, listOf("渔", "渔夫")))
    }

    @Test fun noFalseMatches() {
        assertNull(WordMatch.find("The cat sleeps.", Lang.EN, listOf("dog")))
        assertNull(WordMatch.find("La mer est calme.", Lang.FR, listOf("me")))
        assertNull(WordMatch.find("", Lang.FR, listOf("mer")))
    }
}
