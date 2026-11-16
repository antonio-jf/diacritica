package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FalseFriendsTest {
    private val index = FalseFriendIndex.parse(File("src/main/assets/falsefriends/false_friends.json").readText())

    @Test fun bundledListLoads() {
        assertTrue("expected at least 100 pairs, got ${index.size}", index.size >= 100)
    }

    @Test fun embarazadaWarnsAgainstEnglish() {
        val w = index.warnings(Lang.ES, "embarazada", listOf(Lang.EN, Lang.ES))
        assertEquals(1, w.size)
        assertEquals("embarrassed", w[0].otherWord)
        assertEquals("pregnant", w[0].means)
        assertEquals("avergonzado/a", w[0].sayInstead)
    }

    @Test fun worksFromBothSidesAndIgnoresCaseAndAccents() {
        val w = index.warnings(Lang.EN, "Embarrassed", listOf(Lang.ES))
        assertEquals("embarazada", w.single().otherWord)
        assertEquals(Lang.ES, w.single().otherLang)
        assertEquals("gift", index.warnings(Lang.DE, "Gift", listOf(Lang.EN)).single().otherWord)
        assertEquals("Gift", index.warnings(Lang.EN, "gift", listOf(Lang.DE)).single().otherWord)
        assertTrue(index.warnings(Lang.ES, "exito", listOf(Lang.EN)).isNotEmpty())
    }

    @Test fun onlyMentionsActiveLanguages() {
        assertTrue(index.warnings(Lang.ES, "embarazada", listOf(Lang.DE)).isEmpty())
        val all = index.warnings(Lang.ES, "embarazada", Lang.entries)
        assertEquals(setOf(Lang.EN, Lang.IT, Lang.FR), all.map { it.otherLang }.toSet())
    }

    @Test fun stripsArticles() {
        assertEquals("camera", index.warnings(Lang.IT, "la camera", listOf(Lang.EN)).single().otherWord)
    }

    @Test fun warningsForDeduplicates() {
        val w = index.warningsFor(listOf(Lang.ES to "embarazada", Lang.EN to "embarrassed"), listOf(Lang.EN, Lang.ES))
        assertEquals(1, w.size)
    }

    @Test fun noFalseAlarmsOnOrdinaryWords() {
        assertTrue(index.warnings(Lang.ES, "casa", Lang.entries).isEmpty())
        assertTrue(index.warnings(Lang.EN, "house", Lang.entries).isEmpty())
    }
}
