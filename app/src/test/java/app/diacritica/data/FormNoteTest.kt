package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormNoteTest {
    @Test fun parsesWiktionaryFormGlosses() {
        assertEquals(FormNote("nehmen", "first/third-person singular preterite"), FormNoteText.parse("nehmen", "first/third-person singular preterite of nehmen"))
        assertEquals("past participle", FormNoteText.parse("mangiare", "past participle of mangiare.").description)
        assertEquals("simple past", FormNoteText.parse("take", "simple past of take").description)
        assertEquals("third-person singular imperfect indicative", FormNoteText.parse("aller", "third-person singular imperfect indicative of aller").description)
        // Unexpected wording: keep it as it is.
        assertEquals("plural", FormNoteText.parse("Haus", "plural").description)
    }
}
