package app.diacritica.tests

import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.GrammarTopics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How long the sentences of a generated test are. */
enum class SentenceLength(val label: String, val words: String) {
    SHORT("Short", "4–8 words"),
    MEDIUM("Medium", "8–15 words"),
    LONG("Long", "15–25 words, with a subordinate clause"),
}

/** What to ask for: levels, length, how many sentences, and an optional grammar focus or topic. */
data class TestRequest(
    val level: String,
    val hsk: Int,
    val length: SentenceLength,
    val count: Int,
    /** A GrammarTopics key, or "mixed". */
    val focus: String = "mixed",
    val topic: String = "",
    /** A grammar point every sentence must practise, in words (from an Ask conversation); empty for none. */
    val practise: String = "",
)

/** Writes a new test with Gemini: English sentences with their Spanish version, to translate into your languages. */
object TestGenerator {
    val LEVELS = listOf("A2", "B1", "B2", "C1")
    val COUNTS = listOf(6, 8, 10, 12)

    fun prompt(r: TestRequest): String = buildString {
        appendLine("Write a translation test of ${r.count} sentences for a learner (native English and Spanish speaker).")
        appendLine("Level: ${r.level} (CEFR) for French, German and Italian; the sentences must also be translatable at HSK ${r.hsk} for Mandarin.")
        appendLine("Length: ${r.length.words} each.")
        if (r.focus != "mixed") appendLine("Every sentence must require: ${GrammarTopics.byKey(r.focus).instruction}.")
        else appendLine("Mix grammar points that learners at this level often get wrong (tenses, pronouns, agreement, prepositions, word order…).")
        if (r.practise.isNotBlank()) appendLine("Every sentence must practise this, so that translating it into the target language needs it: ${r.practise.trim()}.")
        if (r.topic.isNotBlank()) appendLine("Theme: ${r.topic.trim()}.")
        appendLine("Natural, everyday sentences with varied situations; no two alike.")
        appendLine("For each: \"en\" (English), \"es\" (the same sentence in natural Spanish), and \"hint\" (optional, a few words naming the trap, e.g. \"German: dative after mit\"; empty if none).")
        append("Also a short \"title\" (2–5 words) and a one-sentence \"description\".")
    }

    private fun schema() = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "STRING") }
            putJsonObject("description") { put("type", "STRING") }
            putJsonObject("items") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("en") { put("type", "STRING") }
                        putJsonObject("es") { put("type", "STRING") }
                        putJsonObject("hint") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("en"); add("es") }
                }
            }
        }
        putJsonArray("required") { add("title"); add("description"); add("items") }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

    fun parse(out: JsonObject, r: TestRequest, now: Long): TestSet? {
        val items = (out["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val en = o.str("en") ?: return@mapNotNull null
            TestItem(en, o.str("es") ?: en, o.str("hint"))
        }.distinctBy { it.en.lowercase() }.take(r.count)
        if (items.size < 3) return null
        val focusLabel = if (r.focus == "mixed") "" else " · " + GrammarTopics.byKey(r.focus).label
        return TestSet(
            id = "gen-$now",
            title = out.str("title") ?: "${r.level} test$focusLabel",
            level = r.level, hsk = r.hsk, focus = r.focus,
            description = (out.str("description") ?: "") + " (${r.length.label.lowercase()} sentences$focusLabel)",
            items = items,
        )
    }

    suspend fun generate(gemini: GeminiClient, apiKey: String, model: String, r: TestRequest): TestSet {
        val out = gemini.generateJson(apiKey, model, prompt(r), schema(), "You write translation tests for language learners. Return only JSON.")
        return parse(out, r, System.currentTimeMillis()) ?: throw GeminiClient.GeminiException("Gemini didn't return a usable test. Try again.")
    }
}
