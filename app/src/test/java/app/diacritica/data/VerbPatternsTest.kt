package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbPatternsTest {
    @Test fun reflexiveWithPreposition() {
        val p = VerbPatterns.parse("(reflexive) to look forward to [with auf (+ accusative)]", listOf("reflexive"), "to look forward to")
        assertEquals(listOf(VerbPattern(true, "auf", "accusative", "to look forward to")), p)
        assertEquals("sich freuen auf + Akk.", p.single().text("freuen"))
    }

    @Test fun prepositionWithoutSich() {
        val p = VerbPatterns.parse("(intransitive) to wait (for) [with auf (+ accusative)]", listOf("intransitive"), "to wait (for)")
        assertEquals("warten auf + Akk.", p.single().text("warten"))
    }

    @Test fun bareCases() {
        assertEquals("sich freuen + Gen.", VerbPatterns.parse("(reflexive, poetical) to take delight in [with genitive]", emptyList(), "x").single().text("freuen"))
        assertEquals("helfen + Dat.", VerbPatterns.parse("(intransitive, + dative) to help", emptyList(), "to help").single().text("helfen"))
    }

    @Test fun reflexiveOnlyAndNothing() {
        assertEquals("sich beeilen", VerbPatterns.parse("(reflexive) to hurry", emptyList(), "to hurry").single().text("beeilen"))
        assertTrue(VerbPatterns.parse("(transitive) to maintain", listOf("transitive"), "to maintain").isEmpty())
        assertTrue(VerbPatterns.parse("to decide [with an infinitive]", emptyList(), "to decide").isEmpty())
    }

    @Test fun alternatives() {
        val p = VerbPatterns.parse("to think [with an (+ dative) or über (+ accusative)]", emptyList(), "to think")
        assertEquals(listOf("denken an + Dat.", "denken über + Akk."), p.map { it.text("denken") })
    }
}
