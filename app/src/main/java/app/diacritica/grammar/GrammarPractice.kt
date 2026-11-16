package app.diacritica.grammar

import app.diacritica.rehearse.GeminiClient
import app.diacritica.tests.TestItem
import app.diacritica.tests.TestSet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Writes a short practice test (English sentences to translate) aimed at one weak point. */
object GrammarPractice {
    const val SENTENCES = 6

    fun prompt(w: WeakPoint, levelEu: String, hsk: Int): String = buildString {
        appendLine("Write $SENTENCES English sentences for a learner to translate into ${w.lang.englishName}.")
        appendLine("Each sentence must make them use: ${GrammarStats.describe(w)}.")
        appendLine("Level: $levelEu (CEFR)" + if (w.lang.code == "zh") ", HSK $hsk vocabulary." else ".")
        if (w.examples.isNotEmpty()) {
            appendLine("Mistakes they made (wrong → right), so target the same rules in new contexts, without reusing these sentences:")
            w.examples.forEach { appendLine("- \"${it.wrong}\" → \"${it.right}\"" + if (it.explanation.isNotBlank()) " (${it.explanation.take(160)})" else "") }
        }
        appendLine("Vary topics and length (6–16 words). Natural, everyday English.")
        append("\"title\": 2–5 words naming the point, e.g. \"German dative after prepositions\".")
    }

    private fun schema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "STRING") }
            putJsonObject("sentences") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
        }
        putJsonArray("required") { add("title"); add("sentences") }
    }

    fun parse(out: JsonObject, w: WeakPoint, levelEu: String, hsk: Int, now: Long): TestSet? {
        val sentences = (out["sentences"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinct().take(SENTENCES)
        if (sentences.size < 3) return null
        val title = (out["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "${w.lang.englishName}: ${w.label}"
        return TestSet(
            id = "weak-${w.lang.code}-${w.type.replace(' ', '-')}-$now",
            title = "Practice: $title",
            level = levelEu, hsk = hsk,
            focus = GrammarStats.describe(w),
            description = "Built from your ${w.label.lowercase()} mistakes in ${w.lang.englishName}.",
            items = sentences.map { TestItem(it, it) },
        )
    }

    suspend fun generate(gemini: GeminiClient, apiKey: String, model: String, w: WeakPoint, levelEu: String, hsk: Int): TestSet {
        val out = gemini.generateJson(apiKey, model, prompt(w, levelEu, hsk), schema(),
            "You write translation exercises for language learners. Return only JSON.")
        return parse(out, w, levelEu, hsk, System.currentTimeMillis())
            ?: throw GeminiClient.GeminiException("Gemini didn't return usable sentences. Try again.")
    }
}
