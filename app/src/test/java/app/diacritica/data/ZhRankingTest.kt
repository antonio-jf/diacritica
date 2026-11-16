package app.diacritica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZhRankingTest {
    private fun r(p: String, defs: String, simp: String = "看", trad: String = simp) =
        ZhReading(simp, trad, p, defs.split(" / "), null, null)

    @Test fun everydayReadingFirst() {
        val rows = listOf(
            r("kān", "to look after / to take care of / to watch / to guard"),
            r("kàn", "to see; to look at / to read / to watch / to visit; to call on / to consider"),
        )
        assertEquals("kàn", ZhRanking.readings("看", rows, emptyMap(), main = "kàn").first().pinyin)
        // Without the curated list, more definitions wins…
        assertEquals("kàn", ZhRanking.readings("看", rows, emptyMap(), main = null).first().pinyin)
        // …but Wiktionary links beat that.
        assertEquals("kān", ZhRanking.readings("看", rows, mapOf("kān" to 3), main = null).first().pinyin)
    }

    @Test fun properNamesLast() {
        val rows = listOf(r("Dū", "surname Du", "都"), r("dōu", "all; both; entirely / (used for emphasis) even / already", "都"), r("dū", "capital city / metropolis", "都"))
        assertEquals(listOf("dōu", "dū", "Dū"), ZhRanking.readings("都", rows, emptyMap(), main = null).map { it.pinyin })
    }

    @Test fun sameReadingMerged() {
        val rows = listOf(r("chī", "to eat; to consume / to absorb", "吃"), r("chī", "variant of 吃[chi1]", "吃", "喫"))
        val out = ZhRanking.readings("吃", rows, emptyMap(), main = null)
        assertEquals(1, out.size)
        assertEquals(listOf("to eat; to consume", "to absorb", "variant of 吃[chi1]"), out[0].definitions)
    }

    @Test fun meaningsKeepOrder() {
        val defs = listOf("to eat; to consume", "to absorb", "to suffer (shock, injury, defeat etc)")
        assertEquals(listOf(0 to "to eat", 1 to "to consume", 10 to "to absorb", 20 to "to suffer (shock, injury, defeat etc)"), ZhRanking.meanings(defs))
        assertEquals(0, ZhRanking.position("eat", defs))
        assertEquals(20, ZhRanking.position("suffer", defs))
        assertNull(ZhRanking.position("drink", defs))
        assertEquals("set off", ZhRanking.core("to set off (fireworks)"))
    }

    @Test fun conceptsFollowTheChineseDictionary() {
        val defs = "to put / to place / to release / to free / to let go / to let out / to set off (fireworks)".split(" / ")
        val linked = listOf("lay", "put", "set", "pose", "play", "place").mapIndexed { i, w -> Concept(i.toLong(), w, "verb", "") }
        assertEquals(listOf("put", "place", "set", "lay", "pose", "play"), ZhRanking.orderConcepts(linked, defs).map { it.enWord })
        val cands = ZhRanking.candidates(defs).map { it.second }
        assertEquals(listOf("put", "place", "release", "free", "let go", "let out"), cands)
        assertTrue(ZhRanking.candidates(listOf("surname Du", "variant of 吃[chi1]", "(used for emphasis) even")).map { it.second } == listOf("even"))
    }

    @Test fun bundledListsLoad() {
        val lex = ZhLexicon.parse(
            File("src/main/assets/zh/hsk.tsv").readText(),
            File("src/main/assets/zh/main_readings.tsv").readText(),
        )
        assertEquals(1, lex.hsk("放"))
        assertEquals(1, lex.hsk("老师"))
        assertEquals("kàn", lex.mainReading("看"))
        assertEquals("xíng", lex.mainReading("行"))
        assertEquals("HSK 7–9", ZhRanking.hskLabel(7))
    }

    @Test fun rareCharacters() {
        val lex = ZhLexicon.parse(
            File("src/main/assets/zh/hsk.tsv").readText(),
            File("src/main/assets/zh/main_readings.tsv").readText(),
            File("src/main/assets/zh/char_rank.txt").readText(),
        )
        assertEquals(1, lex.charRank('的'))
        assertTrue(!lex.hasRareChar("老师", 1000))   // 师 ranks high thanks to 老师
        assertTrue(lex.hasRareChar("渔夫", 1000))    // 渔 is uncommon
        assertTrue(!lex.hasRareChar("渔夫", 3000))   // …but within the top 3,000
        assertTrue(!lex.hasRareChar("，", 10))        // punctuation is never "rare"
        assertTrue(lex.hasRareChar("龘", 3000))       // unranked
    }
}
