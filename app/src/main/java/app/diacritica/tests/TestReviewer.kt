package app.diacritica.tests

import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.GradeError
import app.diacritica.rehearse.GrammarTopics
import app.diacritica.rehearse.Verdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Sends a whole test to Gemini in one request and turns the answer into a [TestReview]. */
object TestReviewer {
    val SYSTEM = """
        You are an experienced, encouraging language teacher marking a translation test.
        The learner is a native speaker of English and Spanish, at B1–B2 in French, German and Italian, and about HSK 2 in Mandarin.
        Write everything in English (examples in the language they illustrate).

        For EVERY answer listed, return one entry in "answers" with:
        - item: the sentence number as given; lang: the language code as given.
        - score 0–100: 100 = correct and natural; 85–99 = correct with a small slip or a less natural choice;
          60–84 = meaning clear but with one or two grammar or spelling errors; 30–59 = several errors or part of the meaning lost;
          1–29 = mostly wrong; 0 = empty or unrelated. Missing accents are a small slip; a wrong tone in pinyin is a small slip.
        - verdict: "correct" (≥ 85), "acceptable" (60–84) or "incorrect" (< 60).
        - corrected: the learner's answer with the smallest changes that make it right (keep their wording when it is valid; repeat it if already right).
        - errors: one item per real error (type, wrong fragment, right fragment, a one- or two-sentence explanation naming the rule). No stylistic preferences.
        - comment: optional, one short sentence (a more natural alternative, register…).
        Other correct translations than the obvious one must get full marks.

        Then:
        - summary: 2–4 sentences: what the learner did well and the main things to work on, per language where it differs.
        - lessons: 1–4 short grammar lessons for the most important RECURRING or serious problems you saw (none if everything was right;
          then one lesson that takes them a step further on the test's focus). Each lesson:
          title; lang (code); why (which sentences showed it, e.g. "Sentences 2 and 5"); explanation (the rule in 3–6 sentences, with
          the contrast that trips learners up); 3 examples with English translations; 2 new English sentences to practise it.
        Formatting inside strings: plain sentences; you may mark key words with **bold** or *italics*; no headings, lists or tables.
    """.trimIndent()

    fun schema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("answers") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("item") { put("type", "INTEGER") }
                        putJsonObject("lang") { put("type", "STRING") }
                        putJsonObject("score") { put("type", "INTEGER") }
                        putJsonObject("verdict") { put("type", "STRING"); putJsonArray("enum") { add("correct"); add("acceptable"); add("incorrect") } }
                        putJsonObject("corrected") { put("type", "STRING") }
                        putJsonObject("errors") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("type") { put("type", "STRING"); putJsonArray("enum") { GeminiClient.ERROR_TYPES.forEach { add(it) } } }
                                    putJsonObject("wrong") { put("type", "STRING") }
                                    putJsonObject("right") { put("type", "STRING") }
                                    putJsonObject("explanation") { put("type", "STRING") }
                                }
                                putJsonArray("required") { add("type"); add("wrong"); add("right"); add("explanation") }
                            }
                        }
                        putJsonObject("comment") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("item"); add("lang"); add("score"); add("verdict"); add("corrected"); add("errors") }
                }
            }
            putJsonObject("summary") { put("type", "STRING") }
            putJsonObject("lessons") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "STRING") }
                        putJsonObject("lang") { put("type", "STRING") }
                        putJsonObject("why") { put("type", "STRING") }
                        putJsonObject("explanation") { put("type", "STRING") }
                        putJsonObject("examples") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("text") { put("type", "STRING") }
                                    putJsonObject("translation") { put("type", "STRING") }
                                }
                                putJsonArray("required") { add("text"); add("translation") }
                            }
                        }
                        putJsonObject("practice") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
                    }
                    putJsonArray("required") { add("title"); add("lang"); add("why"); add("explanation"); add("examples"); add("practice") }
                }
            }
        }
        putJsonArray("required") { add("answers"); add("summary"); add("lessons") }
    }

    /** The request text: the test's focus, then each sentence (numbered from 1) with every answer. */
    fun prompt(test: TestSet, attempt: TestAttempt): String = buildString {
        val focus = when {
            test.focus == "mixed" -> "mixed grammar"
            GrammarTopics.all.any { it.key == test.focus } -> GrammarTopics.byKey(test.focus).instruction
            else -> test.focus
        }
        appendLine("Test: \"${test.title}\" — level ${test.level} (HSK ${test.hsk} for Mandarin). Focus: $focus.")
        appendLine("The learner translated each sentence from ${attempt.source.englishName} into: " + attempt.langs.joinToString { "${it.code} (${it.englishName})" } + ".")
        appendLine("Mandarin answers may be in characters or pinyin.")
        appendLine()
        test.items.forEachIndexed { i, item ->
            appendLine("Sentence ${i + 1}: ${item.prompt(attempt.source)}")
            attempt.langs.forEach { l ->
                val a = attempt.answer(i, l).trim()
                appendLine("  ${l.code}: " + if (a.isEmpty()) "(no answer)" else "\"$a\"")
            }
            appendLine()
        }
        append("Return one entry in \"answers\" for every sentence and language above (${test.items.size * attempt.langs.size} in total).")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()
    private fun lang(code: String?): Lang? = Lang.fromCode(code?.lowercase()?.trim()?.take(2) ?: "")

    /** Parses Gemini's JSON. Sentence numbers in the response are 1-based; [AnswerReview.item] is 0-based. */
    fun parse(out: JsonObject, test: TestSet, attempt: TestAttempt, model: String? = null): TestReview {
        val raw = out.arr("answers").mapNotNull { it as? JsonObject }.mapNotNull { r ->
            val item = ((r["item"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null) - 1
            val lang = lang(r.str("lang")) ?: return@mapNotNull null
            val score = (r["score"] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 0
            AnswerReview(
                item = item, lang = lang, answer = attempt.answer(item, lang), score = score,
                verdict = when (r.str("verdict")) { "correct" -> Verdict.CORRECT; "acceptable" -> Verdict.ACCEPTABLE; "incorrect" -> Verdict.INCORRECT; else -> Verdict.UNGRADED },
                corrected = r.str("corrected"),
                errors = r.arr("errors").mapNotNull { it as? JsonObject }.map {
                    GradeError(it.str("type") ?: "other", it.str("wrong") ?: "", it.str("right") ?: "", it.str("explanation") ?: "")
                },
                comment = r.str("comment"),
            )
        }
        val lessons = out.arr("lessons").mapNotNull { it as? JsonObject }.mapNotNull { l ->
            Lesson(
                title = l.str("title") ?: return@mapNotNull null,
                lang = lang(l.str("lang")),
                why = l.str("why").orEmpty(),
                explanation = l.str("explanation") ?: return@mapNotNull null,
                examples = l.arr("examples").mapNotNull { it as? JsonObject }.mapNotNull { e ->
                    LessonExample(e.str("text") ?: return@mapNotNull null, e.str("translation").orEmpty())
                },
                practice = l.arr("practice").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } },
            )
        }
        return TestReview(
            answers = TestScoring.complete(attempt, test.items.size, raw),
            summary = out.str("summary") ?: "",
            lessons = lessons,
            model = model,
        )
    }

    suspend fun review(gemini: GeminiClient, apiKey: String, model: String, test: TestSet, attempt: TestAttempt): TestReview {
        val out = gemini.generateJson(apiKey, model, prompt(test, attempt), schema(), SYSTEM)
        return parse(out, test, attempt, model)
    }
}
