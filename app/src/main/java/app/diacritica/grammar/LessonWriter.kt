package app.diacritica.grammar

import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import app.diacritica.tests.Lesson
import app.diacritica.tests.LessonExample
import app.diacritica.tests.LessonPitfall
import app.diacritica.tests.LessonSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Writes the full version of a Grammar lesson: an overview, how it's formed, a section per use or form with
 * annotated examples, typical mistakes, how English and Spanish compare, a rule of thumb and graded practice.
 * The short lesson (from a test review or a passage breakdown) is the starting point; its title, language and
 * origin are kept.
 */
object LessonWriter {
    /** Same title in the same language = same lesson (as Grammar › Lessons groups them). */
    fun key(l: Lesson): String = (l.lang?.code ?: "") + ":" + l.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val SYSTEM = """
        You are an expert, patient language teacher writing a complete grammar lesson for one learner.
        The learner is a native speaker of English and Spanish, at B1–B2 in French, German and Italian, and about HSK 2 in Mandarin.
        Write explanations in English; examples in the language being taught. Be accurate and concrete; never invent rules.
        Prefer natural, everyday examples. Formatting inside strings: plain sentences; you may mark key words with **bold**;
        no headings, lists or tables inside strings. Return only JSON matching the schema.
    """.trimIndent()

    fun prompt(l: Lesson, level: String, hsk: Int): String = buildString {
        val lang = l.lang
        val name = lang?.englishName ?: "the language"
        appendLine("Lesson: “${l.title}”" + (lang?.let { " (${it.englishName})" } ?: "") + ".")
        appendLine("Learner level: " + if (lang == Lang.ZH) "about HSK $hsk." else "$level (CEFR).")
        if (l.why.isNotBlank()) appendLine("Where it came from: ${l.why}")
        appendLine("The short version they already have:")
        appendLine(l.explanation)
        l.examples.forEach { appendLine("- ${it.text} — ${it.translation}") }
        appendLine()
        appendLine("Write the full lesson:")
        appendLine("- overview: 4–6 sentences: what this is, what it expresses, and when you use it.")
        appendLine("- pattern: how it's built in $name, one compact formula per line (e.g. \"subject + 把 + object + verb + result\").")
        appendLine("- sections: 3–5 sections, each one use, form or exception (regular cases first, then irregular forms, " +
            "special cases, register). Each: heading (a few words); text (3–6 sentences explaining it); examples: 2–3 new " +
            "sentences in $name, each with translation (English), " +
            (if (lang == Lang.ZH) "reading (pinyin with tone marks), " else "reading (empty), ") +
            "and note (one sentence pointing at what the example shows).")
        appendLine("- pitfalls: 3–4 mistakes English or Spanish speakers typically make with this: wrong, right, note (why).")
        appendLine("- contrast: 3–5 sentences on how English and Spanish express the same idea, and where Spanish helps or misleads.")
        appendLine("- tip: one short rule of thumb to remember it by.")
        append("- practice: 6 English sentences to translate into $name that use the point, from easy to harder.")
    }

    fun schema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("overview") { put("type", "STRING") }
            putJsonObject("pattern") { put("type", "STRING") }
            putJsonObject("sections") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("heading") { put("type", "STRING") }
                        putJsonObject("text") { put("type", "STRING") }
                        putJsonObject("examples") { example() }
                    }
                    putJsonArray("required") { add("heading"); add("text"); add("examples") }
                }
            }
            putJsonObject("pitfalls") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        listOf("wrong", "right", "note").forEach { k -> putJsonObject(k) { put("type", "STRING") } }
                    }
                    putJsonArray("required") { add("wrong"); add("right"); add("note") }
                }
            }
            putJsonObject("contrast") { put("type", "STRING") }
            putJsonObject("tip") { put("type", "STRING") }
            putJsonObject("practice") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
        }
        putJsonArray("required") { add("overview"); add("pattern"); add("sections"); add("pitfalls"); add("contrast"); add("tip"); add("practice") }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.example() {
        put("type", "ARRAY")
        putJsonObject("items") {
            put("type", "OBJECT")
            putJsonObject("properties") {
                listOf("text", "translation", "reading", "note").forEach { k -> putJsonObject(k) { put("type", "STRING") } }
            }
            putJsonArray("required") { add("text"); add("translation") }
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** The short lesson with the full content added. Null if Gemini's answer has no usable sections. */
    fun parse(o: JsonObject, base: Lesson): Lesson? {
        val sections = o.list("sections").mapNotNull { s ->
            LessonSection(
                heading = s.str("heading") ?: return@mapNotNull null,
                text = s.str("text") ?: return@mapNotNull null,
                examples = s.list("examples").mapNotNull { e ->
                    LessonExample(e.str("text") ?: return@mapNotNull null, e.str("translation").orEmpty(), e.str("reading").orEmpty(), e.str("note").orEmpty())
                },
            )
        }
        if (sections.isEmpty()) return null
        val practice = (o["practice"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        return base.copy(
            explanation = o.str("overview") ?: base.explanation,
            pattern = o.str("pattern").orEmpty(),
            sections = sections,
            pitfalls = o.list("pitfalls").mapNotNull { p ->
                LessonPitfall(p.str("wrong") ?: return@mapNotNull null, p.str("right") ?: return@mapNotNull null, p.str("note").orEmpty())
            },
            contrast = o.str("contrast").orEmpty(),
            tip = o.str("tip").orEmpty(),
            practice = practice.ifEmpty { base.practice },
            full = true,
        )
    }

    suspend fun write(gemini: GeminiClient, apiKey: String, model: String, l: Lesson, level: String, hsk: Int): Lesson {
        val out = gemini.generateJson(apiKey, model, prompt(l, level, hsk), schema(), SYSTEM)
        return parse(out, l) ?: throw GeminiClient.GeminiException("Gemini didn't return a usable lesson. Try again.")
    }
}
