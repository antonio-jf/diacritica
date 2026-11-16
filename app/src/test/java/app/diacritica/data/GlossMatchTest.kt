package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlossMatchTest {
    @Test fun scores() {
        assertEquals(0, GlossMatch.score("trickster", "trickster"))
        assertEquals(0, GlossMatch.score("to daydream", "daydream"))
        assertEquals(1, GlossMatch.score("trickster, swindler", "trickster"))
        assertEquals(2, GlossMatch.score("joker; trickster; prankster", "trickster"))
        assertEquals(2, GlossMatch.score("rabble, rascals", "rascal"))
        assertEquals(12, GlossMatch.score("cheat, swindler, confidence trickster", "trickster"))
        assertEquals(0, GlossMatch.score("awkward (e.g. situation or position)", "awkward"))
        assertNull(GlossMatch.score("tricky", "trick"))
        assertNull(GlossMatch.score("a con artist", "trickster"))
    }

    @Test fun ranking() {
        fun c(w: String, g: String, pos: String = "noun") = GlossMatch.Candidate(w, pos, null, g)
        val out = GlossMatch.rank(
            listOf(
                c("imbroglione", "cheat, swindler, confidence trickster"),
                c("embaucador", "trickster, swindler"),
                c("filou", "trickster"),
                c("filou", "trickster (again)"),
                c("pensar en la inmortalidad del cangrejo", "trickster"),
                c("tricksen", "to trick", "verb"),
            ),
            "trickster", "noun",
        )
        assertEquals(listOf("filou", "embaucador", "pensar en la inmortalidad del cangrejo"), out.map { it.word })
        // Wrong part of speech is pushed down.
        val verbs = GlossMatch.rank(listOf(c("rêverie", "daydream"), c("rêvasser", "to daydream", "verb")), "daydream", "verb")
        assertEquals("rêvasser", verbs.first().word)
    }
}
