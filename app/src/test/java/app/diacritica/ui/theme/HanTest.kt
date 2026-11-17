package app.diacritica.ui.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HanTest {
    @Test fun detectsChinese() {
        assertTrue(Han.hasHan("起床"))
        assertTrue(Han.hasHan("measure word 把 bǎ"))
        assertFalse(Han.hasHan("sich freuen auf + Akk."))
        assertFalse(Han.hasHan("qǐchuáng"))
    }

    @Test fun runsCoverCharactersAndPunctuation() {
        assertEquals(listOf(0 to 4), Han.runs("我起床。"))
        // "老实 lǎoshi · honest" → just the characters
        assertEquals(listOf(0 to 2), Han.runs("老实 lǎoshi · honest"))
        // spaces between characters stay inside one run, trailing ones don't
        assertEquals(listOf(0 to 5), Han.runs("钥匙 鑰匙 yàoshi"))
        assertEquals(listOf(13 to 14), Han.runs("measure word 把 bǎ"))
    }

    @Test fun scalesOnlyChinese() {
        val s = Han.scale("老实 lǎoshi", 1.2f)
        assertEquals(1, s.spanStyles.size)
        val span = s.spanStyles[0]
        assertEquals(0, span.start); assertEquals(2, span.end)
        assertEquals(1.2f.em, span.item.fontSize)
    }

    @Test fun latinTextUnchanged() {
        val a = AnnotatedString("warten auf + Akk.")
        assertSame(a, Han.scale(a, 1.3f))
    }
}
