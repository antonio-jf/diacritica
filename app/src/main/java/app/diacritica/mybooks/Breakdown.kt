package app.diacritica.mybooks

import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import app.diacritica.tests.Lesson
import app.diacritica.tests.LessonExample
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One word of the passage, as it appears, with its reading, dictionary form, meaning here and grammatical role. */
@Serializable
data class GlossWord(val text: String, val reading: String? = null, val lemma: String? = null, val meaning: String = "", val role: String? = null)

@Serializable
data class GrammarPoint(
    val title: String,
    /** The pattern in the book's language, e.g. "了 after a verb", "si + imparfait, conditionnel". */
    val pattern: String = "",
    /** The words of the passage that show it. */
    val inPassage: String = "",
    val explanation: String,
    val examples: List<LessonExample> = emptyList(),
    /** English sentences to translate into the book's language for practice. */
    val practice: List<String> = emptyList(),
)

@Serializable
data class VocabItem(val word: String, val reading: String? = null, val meaning: String, val note: String? = null)

/** Gemini's explanation of a passage from a book. Stored as JSON with the note, so it's asked for once. */
@Serializable
data class Breakdown(
    val translation: String,
    /** A closer, word-for-word rendering, when it helps (Mandarin especially). */
    val literal: String? = null,
    val words: List<GlossWord> = emptyList(),
    val grammar: List<GrammarPoint> = emptyList(),
    val vocabulary: List<VocabItem> = emptyList(),
) {
    /** A Grammar-section lesson from one of the points, with the passage as where it came from. */
    fun lesson(point: GrammarPoint, lang: Lang, bookTitle: String, passage: String): Lesson = Lesson(
        title = point.title,
        lang = lang,
        why = "From “$bookTitle”: " + passage.replace('\n', ' ').let { if (it.length > 140) it.take(137) + "…" else it },
        explanation = buildString {
            if (point.pattern.isNotBlank()) append(point.pattern).append("\n\n")
            append(point.explanation)
            if (point.inPassage.isNotBlank()) append("\n\nIn the passage: ").append(point.inPassage)
        },
        examples = point.examples,
        practice = point.practice,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun encode(b: Breakdown): String = json.encodeToString(serializer(), b)
        fun decode(s: String?): Breakdown? = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        /** Longest passage sent for a breakdown (characters); longer selections are refused with a message. */
        const val MAX_CHARS = 1200

        private val SYSTEM = """
            You are a patient language teacher explaining a passage from a book the learner is reading.
            The learner is a native speaker of English and Spanish, at B1–B2 in French, German and Italian, and about HSK 2 in Mandarin.
            Write every explanation and meaning in English. Be accurate and concrete; never invent grammar.
            Return only JSON matching the schema.
        """.trimIndent()

        fun prompt(passage: String, lang: Lang, bookTitle: String, context: String?): String = buildString {
            appendLine("Book: “$bookTitle” (${lang.englishName}).")
            if (!context.isNullOrBlank() && context != passage) {
                appendLine("Surrounding text, for context only:")
                appendLine(context)
                appendLine()
            }
            appendLine("Break down this passage:")
            appendLine(passage)
            appendLine()
            appendLine("- translation: a natural English translation of the passage.")
            appendLine("- literal: a close word-for-word English rendering if it differs usefully from the translation, else empty.")
            appendLine("- words: every word of the passage in order (skip punctuation). For each: text as written; reading" +
                (if (lang == Lang.ZH) " (pinyin with tone marks)" else " (empty)") +
                "; lemma (dictionary form, empty if the same); meaning in this context (a few words); role: a short grammatical role " +
                "(e.g. \"subject\", \"verb, passé simple\", \"aspect particle: completed action\", \"measure word for flat objects\").")
            appendLine("- grammar: the 1–4 most useful grammar points in the passage for this learner, most important first. For each: " +
                "title (short, e.g. \"了 for completed actions\"); pattern (the structure in ${lang.englishName}); inPassage (the exact words " +
                "showing it); explanation (3–6 sentences); examples: 2–3 new sentences in ${lang.englishName} with English translations; " +
                "practice: 4 new English sentences that make the learner use the point when translating into ${lang.englishName}.")
            appendLine("- vocabulary: up to 6 words or expressions worth learning from the passage (not the most basic ones), " +
                "with reading" + (if (lang == Lang.ZH) " (pinyin)" else " (empty)") + ", meaning, and an optional note (register, usage, false friend).")
        }

        fun schema(): JsonObject = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("translation") { put("type", "STRING") }
                putJsonObject("literal") { put("type", "STRING") }
                putJsonObject("words") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            listOf("text", "reading", "lemma", "meaning", "role").forEach { k -> putJsonObject(k) { put("type", "STRING") } }
                        }
                        putJsonArray("required") { add("text"); add("meaning") }
                    }
                }
                putJsonObject("grammar") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            listOf("title", "pattern", "inPassage", "explanation").forEach { k -> putJsonObject(k) { put("type", "STRING") } }
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
                        putJsonArray("required") { add("title"); add("explanation") }
                    }
                }
                putJsonObject("vocabulary") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            listOf("word", "reading", "meaning", "note").forEach { k -> putJsonObject(k) { put("type", "STRING") } }
                        }
                        putJsonArray("required") { add("word"); add("meaning") }
                    }
                }
            }
            putJsonArray("required") { add("translation"); add("words"); add("grammar"); add("vocabulary") }
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        /** Tolerant parsing: missing optional fields are fine, unusable items are dropped. Null if there's no translation. */
        fun parse(o: JsonObject): Breakdown? {
            val translation = o.str("translation") ?: return null
            return Breakdown(
                translation = translation,
                literal = o.str("literal")?.takeIf { it != translation },
                words = o.list("words").mapNotNull { w ->
                    GlossWord(w.str("text") ?: return@mapNotNull null, w.str("reading"), w.str("lemma")?.takeIf { it != w.str("text") }, w.str("meaning") ?: "", w.str("role"))
                },
                grammar = o.list("grammar").mapNotNull { g ->
                    GrammarPoint(
                        title = g.str("title") ?: return@mapNotNull null,
                        pattern = g.str("pattern") ?: "",
                        inPassage = g.str("inPassage") ?: "",
                        explanation = g.str("explanation") ?: return@mapNotNull null,
                        examples = g.list("examples").mapNotNull { e -> LessonExample(e.str("text") ?: return@mapNotNull null, e.str("translation") ?: "") },
                        practice = (g["practice"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } },
                    )
                }.take(4),
                vocabulary = o.list("vocabulary").mapNotNull { v ->
                    VocabItem(v.str("word") ?: return@mapNotNull null, v.str("reading"), v.str("meaning") ?: return@mapNotNull null, v.str("note"))
                }.take(8),
            )
        }

        suspend fun request(gemini: GeminiClient, apiKey: String, model: String, passage: String, lang: Lang, bookTitle: String, context: String?): Breakdown {
            val out = gemini.generateJson(apiKey, model, prompt(passage, lang, bookTitle, context), schema(), SYSTEM)
            return parse(out) ?: throw GeminiClient.GeminiException("Gemini didn't return a usable breakdown. Try again.")
        }
    }
}
