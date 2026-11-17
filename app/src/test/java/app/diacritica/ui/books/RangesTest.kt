package app.diacritica.ui.books

import app.diacritica.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

class RangesTest {
    private val fr = "Il était une fois un pêcheur. Il vivait près de la mer."

    @Test fun sentenceAroundAnOffset() {
        val r = sentenceRange(fr, 35, Lang.FR)
        assertEquals("Il vivait près de la mer.", fr.substring(r.first, r.last + 1))
        val first = sentenceRange(fr, 0, Lang.FR)
        assertEquals("Il était une fois un pêcheur.", fr.substring(first.first, first.last + 1))
    }

    @Test fun chineseSentences() {
        val zh = "我每天早上七点起床。你呢？"
        val r = sentenceRange(zh, 11, Lang.ZH)
        assertEquals("你呢？", zh.substring(r.first, r.last + 1))
    }

    @Test fun wordAroundAnOffset() {
        val r = wordRange(fr, 5)!!
        assertEquals("était", fr.substring(r.first, r.last + 1))
        val apostrophe = "l'homme regarde"
        val a = wordRange(apostrophe, 2)!!
        assertEquals("l'homme", apostrophe.substring(a.first, a.last + 1))
    }

    @Test fun sentenceAroundIsTheSavedContext() {
        assertEquals("Il vivait près de la mer.", sentenceAround(fr, 40, Lang.FR))
    }
}
