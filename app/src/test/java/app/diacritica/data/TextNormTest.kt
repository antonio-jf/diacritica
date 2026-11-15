package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the Kotlin normalization identical to pipeline/build_dictionary.py (see pipeline/test_build.py). */
class TextNormTest {
    @Test fun normalizeMatchesPipeline() {
        assertEquals("schlussel", TextNorm.normalize("Schlüssel"))
        assertEquals("cle", TextNorm.normalize("  Clé "))
        assertEquals("strasse", TextNorm.normalize("Straße"))
        assertEquals("oeuvre", TextNorm.normalize("œuvre"))
        assertEquals("tonalita", TextNorm.normalize("tonalità"))
    }

    @Test fun pinyin() {
        assertTrue(TextNorm.containsHan("钥匙"))
        assertTrue(TextNorm.looksLikeNumberedPinyin("yao4shi"))
        assertTrue(TextNorm.looksLikeNumberedPinyin("ni3 hao3"))
        assertFalse(TextNorm.looksLikeNumberedPinyin("key"))
        assertEquals("lu3xing2", TextNorm.pinyinNumKey("Lv3 xing2"))
        assertEquals("xian", TextNorm.pinyinToneless("Xī'ān"))
        assertEquals("yaoshi", TextNorm.pinyinToneless("yàoshi"))
    }
}

class ArticlesTest {
    @Test fun articles() {
        assertEquals("la", Articles.forNoun(Lang.FR, "clé", "f", "noun"))
        assertEquals("l'", Articles.forNoun(Lang.FR, "eau", "f", "noun"))
        assertEquals("das", Articles.forNoun(Lang.DE, "Haus", "n", "noun"))
        assertEquals("lo", Articles.forNoun(Lang.IT, "zaino", "m", "noun"))
        assertEquals("lo", Articles.forNoun(Lang.IT, "studente", "m", "noun"))
        assertEquals("l'", Articles.forNoun(Lang.IT, "amico", "m", "noun"))
        assertEquals("il", Articles.forNoun(Lang.IT, "libro", "m", "noun"))
        assertEquals("el", Articles.forNoun(Lang.ES, "agua", "f", "noun"))
        assertEquals("el", Articles.forNoun(Lang.ES, "mar", "m/f", "noun"))
        assertNull(Articles.forNoun(Lang.FR, "clé", "f", "adj"))
    }
}
