package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinTonesTest {
    private fun syl(s: String) = PinyinTones.split(s).filter { it.tone != 0 }.map { "${it.text}${it.tone}" }

    @Test fun spaced() = assertEquals(listOf("zhōng1", "guó2"), syl("zhōng guó"))
    @Test fun joined() {
        assertEquals(listOf("diàn4", "shì4"), syl("diànshì"))
        assertEquals(listOf("zhōng1", "guó2"), syl("zhōngguó"))
        assertEquals(listOf("dàn4", "gāo1"), syl("dàngāo"))
        assertEquals(listOf("nǐ3", "hǎo3"), syl("nǐhǎo"))
        assertEquals(listOf("ér2", "zi5"), syl("érzi"))
        assertEquals(listOf("wǎn3", "shang5"), syl("wǎnshang"))
        assertEquals(listOf("rè4", "nao5"), syl("rènao"))
        assertEquals(listOf("Xī1", "ān1"), syl("Xī'ān"))
    }
    @Test fun numbered() = assertEquals(listOf("zhong1", "guo2"), syl("zhong1 guo2"))
    @Test fun neutralAndUmlaut() {
        assertEquals(listOf("ma5"), syl("ma"))
        assertEquals(listOf("nǚ3", "ér2"), syl("nǚ'ér"))
    }
    @Test fun keepsAllText() {
        val s = "lǎoshi · honest"
        assertEquals(s, PinyinTones.split(s).joinToString("") { it.text })
    }
}
