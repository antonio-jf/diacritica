package app.diacritica.vocab

import app.diacritica.data.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VocabCatalogTest {
    private fun row(en: String, zh: String = "植物", py: String = "zhíwù") =
        """{"en":"$en","fr":"la plante","de":"die Pflanze","it":"la pianta","es":"la planta","zh":"$zh","pinyin":"$py"}"""

    @Test fun catalogIdsAreUnique() {
        val ids = VocabCatalog.topics.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(VocabCatalog.topics.size >= 30)
    }

    @Test fun parsesRowsAndDropsIncompleteOnes() {
        val rows = (1..7).joinToString(",") { row("plant $it") } + """,{"en":"leaf","fr":"la feuille"}"""
        val set = VocabCatalog.parse(Json.parseToJsonElement("""{"title":"Plants","words":[$rows]}""").jsonObject, "plants", "x", "Nature")!!
        assertEquals("Plants", set.title)
        assertEquals(7, set.words.size)
        assertEquals("die Pflanze", set.words[0].form(Lang.DE))
    }

    @Test fun mismatchedAlternativesKeepTheFirst() {
        val rows = (1..6).joinToString(",") { row("w$it", "叶子 / 叶", "yèzi") }
        val set = VocabCatalog.parse(Json.parseToJsonElement("""{"title":"T","words":[$rows]}""").jsonObject, "t", "T", "c")!!
        assertEquals("叶子", set.words[0].form(Lang.ZH))
    }

    @Test fun tooFewWords() {
        assertNull(VocabCatalog.parse(Json.parseToJsonElement("""{"title":"T","words":[${row("a")}]}""").jsonObject, "t", "T", "c"))
    }

    @Test fun storeRoundTrip() {
        val dir = createTempDir()
        val store = CustomVocabStore(File(dir, "vocab/custom.json"))
        val set = VocabSet("plants", "Plants", "Nature", listOf(VocabWord(Lang.entries.associateWith { "x-${it.code}" }, "p")), custom = true)
        store.save(set)
        val back = store.load()
        assertEquals(1, back.size)
        assertEquals("x-de", back[0].words[0].form(Lang.DE))
        assertTrue(back[0].custom)
        store.delete("plants")
        assertTrue(store.load().isEmpty())
        dir.deleteRecursively()
    }
}
