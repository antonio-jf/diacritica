package app.diacritica.vocab

import app.diacritica.data.Lang
import app.diacritica.vocab.VocabCheck.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VocabSetsTest {
    private val sets = VocabSets.parse(File("src/main/assets/vocab/sets.tsv").readText())
    private fun word(set: String, en: String) = sets.first { it.id == set }.words.first { it.form(Lang.EN) == en }

    @Test fun everySetIsComplete() {
        assertTrue(sets.size >= 20)
        assertEquals(sets.size, sets.map { it.id }.toSet().size)
        sets.forEach { s ->
            assertTrue(s.id, s.words.size >= 7)
            s.words.forEach { w ->
                Lang.entries.forEach { l -> assertTrue("${s.id} ${w.form(Lang.EN)} $l", w.form(l).isNotBlank()) }
                assertTrue("${s.id} ${w.form(Lang.EN)} pinyin", w.pinyin.isNotBlank())
                assertEquals("${s.id} ${w.form(Lang.EN)} alternatives", VocabCheck.alternatives(w.form(Lang.ZH)).size, VocabCheck.alternatives(w.pinyin).size)
            }
        }
    }

    @Test fun bareForms() {
        assertEquals("orange", VocabCheck.bare("l'orange (f)", Lang.FR))
        assertEquals("Apfel", VocabCheck.bare("der Apfel", Lang.DE))
        assertEquals("eat", VocabCheck.bare("to eat", Lang.EN))
        assertEquals("pomme de terre", VocabCheck.headword("la pomme de terre", Lang.FR))
        assertEquals("médico", VocabCheck.headword("el médico / la médica", Lang.ES))
    }

    @Test fun grading() {
        val apple = word("fruits", "apple")
        assertEquals(Grade.RIGHT, VocabCheck.check("der Apfel", apple, Lang.DE).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("Apfel", apple, Lang.DE).grade) // article missing: right, with a note
        assertEquals(Grade.ALMOST, VocabCheck.check("die Apfel", apple, Lang.DE).grade)
        assertEquals(Grade.ALMOST, VocabCheck.check("la melocoton", word("fruits", "peach"), Lang.ES).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("l'orange", word("fruits", "orange"), Lang.FR).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("la médica", word("professions", "doctor"), Lang.ES).grade)
        assertEquals(Grade.WRONG, VocabCheck.check("la pera", apple, Lang.ES).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("苹果", apple, Lang.ZH).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("píngguǒ", apple, Lang.ZH).grade)
        assertEquals(Grade.ALMOST, VocabCheck.check("pingguo", apple, Lang.ZH).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("妹妹", word("family", "sister"), Lang.ZH).grade)
        assertEquals(Grade.RIGHT, VocabCheck.check("manger", word("everyday-verbs", "to eat"), Lang.FR).grade)
    }
}
