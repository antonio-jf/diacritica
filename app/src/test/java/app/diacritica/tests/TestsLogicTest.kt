package app.diacritica.tests

import app.diacritica.data.Lang
import app.diacritica.rehearse.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestsLogicTest {
    private val bank = TestBank.parse(File("src/main/assets/tests/tests.json").readText())
    private val test = bank.first()
    private val attempt = TestAttempt(1, test.id, test.title, Lang.EN, listOf(Lang.FR, Lang.DE), startedAt = 0)
        .withAnswer(0, Lang.FR, "Ma sœur a deux chats et un chien.")
        .withAnswer(0, Lang.DE, "Meine Schwester hat zwei Katze und ein Hund.")
        .withAnswer(1, Lang.FR, "À quelle heure tu te lèves le matin ?")

    @Test fun bundledTestsAreWellFormed() {
        assertTrue(bank.size >= 10)
        assertEquals(bank.size, bank.map { it.id }.toSet().size)
        bank.forEach { t ->
            assertEquals(t.id, 8, t.items.size)
            t.items.forEach { assertTrue(it.en.isNotBlank() && it.es.isNotBlank()) }
            assertTrue(t.hsk in 1..6)
        }
        assertEquals("Mi hermana tiene dos gatos y un perro.", test.items[0].prompt(Lang.ES))
    }

    @Test fun answersAndCounts() {
        assertEquals(3, attempt.answeredCount(test.items.size))
        assertEquals("", attempt.answer(5, Lang.FR))
    }

    @Test fun parseFillsGapsAndUsesOneBasedNumbers() {
        val out = Json.parseToJsonElement(
            """{"answers": [
                 {"item": 1, "lang": "fr", "score": 100, "verdict": "correct", "corrected": "Ma sœur a deux chats et un chien.", "errors": []},
                 {"item": 1, "lang": "de", "score": 70, "verdict": "acceptable", "corrected": "Meine Schwester hat zwei Katzen und einen Hund.",
                  "errors": [{"type": "case", "wrong": "ein Hund", "right": "einen Hund", "explanation": "haben takes the accusative."}]},
                 {"item": 2, "lang": "FR", "score": 92.0, "verdict": "correct", "corrected": "x", "errors": []},
                 {"item": 99, "lang": "fr", "score": 50, "verdict": "incorrect", "corrected": "", "errors": []}
               ],
               "summary": "Good French.",
               "lessons": [{"title": "Accusative after haben", "lang": "de", "why": "Sentence 1", "explanation": "…",
                            "examples": [{"text": "Ich habe einen Hund.", "translation": "I have a dog."}], "practice": ["I have a brother.", "We need a car."]},
                           {"lang": "fr", "explanation": "no title, dropped"}]}""",
        ) as JsonObject
        val r = TestReviewer.parse(out, test, attempt, "m")
        assertEquals(test.items.size * 2, r.answers.size)
        val de = r.forItem(0).single { it.lang == Lang.DE }
        assertEquals(70, de.score)
        assertEquals("case", de.errors.single().type)
        assertEquals(attempt.answer(0, Lang.DE), de.answer)
        assertEquals(92, r.forItem(1).single { it.lang == Lang.FR }.score)
        // Not answered → 0, incorrect
        val empty = r.forItem(3).single { it.lang == Lang.DE }
        assertEquals(0, empty.score)
        assertEquals(Verdict.INCORRECT, empty.verdict)
        assertEquals(1, r.lessons.size)
        assertEquals(Lang.DE, r.lessons[0].lang)
        // Mean over all 16 answers: (100 + 70 + 92) / 16
        assertEquals(16, r.score)
        assertEquals(TestScoring.mean(listOf(100, 92, 0, 0, 0, 0, 0, 0)), r.scoreFor(Lang.FR))
    }

    @Test fun completeFixesMissingVerdicts() {
        val a = attempt
        val raw = listOf(AnswerReview(0, Lang.FR, "", 95, Verdict.UNGRADED), AnswerReview(0, Lang.DE, "", 140, Verdict.INCORRECT))
        val done = TestScoring.complete(a, 2, raw)
        assertEquals(4, done.size)
        assertEquals(Verdict.CORRECT, done.first { it.item == 0 && it.lang == Lang.FR }.verdict)
        assertEquals(100, done.first { it.item == 0 && it.lang == Lang.DE }.score)
        val skipped = done.first { it.item == 1 && it.lang == Lang.FR }
        assertEquals(Verdict.UNGRADED, skipped.verdict)
    }

    @Test fun promptListsEveryAnswer() {
        val p = TestReviewer.prompt(test, attempt)
        assertTrue(p.contains("Sentence 1: My sister has two cats and a dog."))
        assertTrue(p.contains("de: \"Meine Schwester hat zwei Katze und ein Hund.\""))
        assertTrue(p.contains("de: (no answer)"))
        assertTrue(p.contains("(${test.items.size * 2} in total)"))
    }

    @Test fun progressFeedbackSkipsUnmarked() {
        val r = TestReview(
            listOf(AnswerReview(0, Lang.FR, "a", 90, Verdict.CORRECT), AnswerReview(1, Lang.FR, "b", 0, Verdict.UNGRADED)), "",
        )
        assertEquals(1, TestScoring.asFeedback(r).size)
    }

    @Test fun lessonPracticeTest() {
        val lesson = Lesson("Accusative after haben", Lang.DE, "Sentence 1", "…", practice = listOf("I have a brother.", "We need a car."))
        val t = TestBank.fromLesson(lesson, test)
        assertEquals("lesson-accusative-after-haben", t.id)
        assertEquals(2, t.items.size)
        assertEquals("I have a brother.", t.items[0].prompt(Lang.ES))
        assertTrue(TestReviewer.prompt(t, attempt.copy(testId = t.id)).contains("Focus: Accusative after haben."))
    }

    @Test fun labels() {
        assertEquals("Excellent", TestScoring.label(95))
        assertEquals(Verdict.ACCEPTABLE, TestScoring.verdictFor(75))
        assertEquals(0, TestScoring.mean(emptyList()))
        assertNull(attempt.review)
    }
}
