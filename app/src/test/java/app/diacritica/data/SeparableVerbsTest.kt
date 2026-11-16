package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeparableVerbsTest {
    private fun f(form: String, vararg tags: String) = FormRow(form, tags.joinToString(","))

    @Test fun anfangenIsSeparable() {
        val sv = SeparableVerbs.detect(
            "anfangen", "verb",
            listOf(f("fange an", "first-person", "singular", "present"), f("fängt an", "third-person", "singular", "present"), f("angefangen", "past", "participle")),
        )!!
        assertEquals("an", sv.prefix)
        assertEquals("fangen", sv.stem)
        assertEquals("fängt … an", sv.pattern)
    }

    @Test fun longestPrefixWins() {
        val sv = SeparableVerbs.detect("zurückkommen", "verb", listOf(f("kommt zurück", "third-person", "singular", "present")))!!
        assertEquals("zurück", sv.prefix)
    }

    @Test fun inseparableVerbsAreNotFlagged() {
        assertNull(SeparableVerbs.detect("verstehen", "verb", listOf(f("versteht", "third-person", "singular", "present"))))
        assertNull(SeparableVerbs.detect("antworten", "verb", listOf(f("antwortet", "third-person", "singular", "present"))))
        // "hat geantwortet" splits, but "geantwortet" is not a prefix.
        assertNull(SeparableVerbs.detect("antworten", "verb", listOf(f("hat geantwortet", "perfect"))))
    }

    @Test fun reflexiveSeparable() {
        val sv = SeparableVerbs.detect("sich anziehen", "verb", listOf(f("zieht an", "third-person", "singular", "present")))!!
        assertEquals("an", sv.prefix)
    }

    @Test fun nonVerbsAndUnknown() {
        assertNull(SeparableVerbs.detect("Anfang", "noun", listOf(f("Anfänge", "plural"))))
        assertNull(SeparableVerbs.detect("anfangen", "verb", emptyList()))
    }

    @Test fun onlyGermanEntries() {
        val e = EntryDetail(Lang.EN, "give up", "verb", null, null, null, emptyList(), listOf(f("gives up", "present")))
        assertNull(SeparableVerbs.detect(e))
    }
}
