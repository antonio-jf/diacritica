package app.diacritica.mybooks

import app.diacritica.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookshelfLogicTest {
    @Test fun languageTags() {
        assertEquals(Lang.FR, Bookshelf.langOf("fr-FR"))
        assertEquals(Lang.ZH, Bookshelf.langOf("zh-Hans"))
        assertEquals(Lang.ZH, Bookshelf.langOf("cmn"))
        assertEquals(Lang.DE, Bookshelf.langOf("ger"))
        assertEquals(Lang.ES, Bookshelf.langOf("es_MX"))
        assertNull(Bookshelf.langOf("ja"))
        assertNull(Bookshelf.langOf(null))
    }

    @Test fun sentencesForRehearseSkipFragments() {
        val s = Bookshelf.splitSentences("« Oui. » Il était une fois un vieux pêcheur. Il vivait seul près de la mer.", Lang.FR)
        assertEquals(listOf("Il était une fois un vieux pêcheur.", "Il vivait seul près de la mer."), s)
    }

    @Test fun chineseSentencesByCharacters() {
        val s = Bookshelf.splitSentences("好。我每天早上七点起床。然后我去上班。", Lang.ZH)
        assertEquals(listOf("我每天早上七点起床。", "然后我去上班。"), s)
    }

    @Test fun wordCounts() {
        assertEquals(4, Bookshelf.countWords("I get up early.", Lang.EN))
        assertEquals(7, Bookshelf.countWords("我每天七点起床。", Lang.ZH))
    }
}
