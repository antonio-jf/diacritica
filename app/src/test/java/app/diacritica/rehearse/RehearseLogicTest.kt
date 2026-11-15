package app.diacritica.rehearse

import app.diacritica.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCheckerTest {
    @Test fun exactMatchIgnoresCaseAndPunctuation() {
        val f = RuleChecker.check(Lang.FR, "le chat dort", listOf("Le chat dort."), strictAccents = true, strictTones = true)
        assertEquals(Verdict.CORRECT, f.verdict)
    }

    @Test fun missingAccentsAreWrongWhenStrictAndAcceptableOtherwise() {
        val strict = RuleChecker.check(Lang.FR, "Ou est la cle ?", listOf("Où est la clé ?"), strictAccents = true, strictTones = true)
        assertEquals(Verdict.INCORRECT, strict.verdict)
        assertEquals("Où est la clé ?", strict.corrected)
        assertTrue(strict.errors.all { it.type == "accents" })
        val lenient = RuleChecker.check(Lang.FR, "Ou est la cle ?", listOf("Où est la clé ?"), strictAccents = false, strictTones = true)
        assertEquals(Verdict.ACCEPTABLE, lenient.verdict)
    }

    @Test fun blankAndUnreferencedAnswers() {
        assertEquals(Verdict.INCORRECT, RuleChecker.check(Lang.DE, " ", listOf("Hallo"), true, true).verdict)
        assertEquals(Verdict.UNGRADED, RuleChecker.check(Lang.DE, "Hallo", emptyList(), true, true).verdict)
    }

    @Test fun pinyinNeedsTonesWhenStrict() {
        val refs = listOf("钥匙")
        val py = listOf("yàoshi")
        assertEquals(Verdict.CORRECT, RuleChecker.check(Lang.ZH, "yàoshi", refs, true, true, py).verdict)
        assertEquals(Verdict.INCORRECT, RuleChecker.check(Lang.ZH, "yaoshi", refs, true, true, py).verdict)
        assertEquals(Verdict.ACCEPTABLE, RuleChecker.check(Lang.ZH, "yaoshi", refs, true, false, py).verdict)
        assertEquals(Verdict.CORRECT, RuleChecker.check(Lang.ZH, "钥匙", refs, true, true, py).verdict)
    }
}

class GrammarTopicsTest {
    @Test fun unknownKeyFallsBackToMixed() {
        assertEquals("mixed", GrammarTopics.byKey("nope").key)
    }

    @Test fun topicsFollowTheLanguages() {
        val de = GrammarTopics.forLanguages(listOf(Lang.DE)).map { it.key }
        assertTrue("cases" in de)
        assertFalse("serestar" in de)
        assertTrue("mixed" in de)
        val zh = GrammarTopics.forLanguages(listOf(Lang.ZH)).map { it.key }
        assertTrue("le" in zh && "ba" in zh)
        assertFalse("subjunctive" in zh)
    }
}
