package app.diacritica.rehearse

import app.diacritica.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Gemini API client (REST, generateContent with a JSON response schema).
 * The API key is the user's own, stored only on the phone.
 */
class GeminiClient {
    private val json = Json { ignoreUnknownKeys = true }

    class GeminiException(message: String) : Exception(message)

    companion object {
        /** Tried in order when the chosen model is unavailable (models get renamed and retired). */
        val FALLBACK_MODELS = listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash")
        const val DEFAULT_MODEL = "gemini-3.5-flash"

        val ERROR_TYPES = listOf(
            "gender", "agreement", "conjugation", "tense", "mood", "case", "word-order", "accents", "tones",
            "aspect", "articles", "prepositions", "word-choice", "spelling", "register", "missing-word",
            "extra-word", "measure-word", "other",
        )

        private val SYSTEM = """
            You are a precise, encouraging language teacher grading a learner's translations.
            The learner is a native speaker of English and Spanish, at B1–B2 in French, German and Italian, and about HSK 2 in Mandarin.
            Write every explanation in English.

            For each answer:
            - verdict: "correct" (right and natural), "acceptable" (right meaning and grammar, but unidiomatic or a different nuance), or "incorrect" (a grammar, spelling or meaning error).
            - The reference translations are examples only: other correct translations must be accepted.
            - corrected: the learner's answer with the smallest changes that make it correct (keep their wording when it is valid). If the answer is already correct, repeat it.
            - errors: one item per real error, with the wrong fragment, the right fragment and a full grammar explanation:
              name the rule, say why it applies here, and give one short contrasting example. Do not list stylistic preferences as errors.
            - alternatives: one or two natural ways a native speaker would say it.
            - note: optional one-sentence remark (e.g. regional usage, register).
            Accents count as errors when "accents required" is yes. For Mandarin answers typed in pinyin, tones count when "tones required" is yes.
            An empty answer is "incorrect" with the note "No answer".
            Formatting: plain sentences. You may mark key words with **bold** or *italics*; no headings, bullet lists or tables.
        """.trimIndent()
    }

    private fun schemaGrade() = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("results") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("lang") { put("type", "STRING") }
                        putJsonObject("verdict") {
                            put("type", "STRING")
                            putJsonArray("enum") { add("correct"); add("acceptable"); add("incorrect") }
                        }
                        putJsonObject("corrected") { put("type", "STRING") }
                        putJsonObject("errors") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("type") { put("type", "STRING"); putJsonArray("enum") { ERROR_TYPES.forEach { add(it) } } }
                                    putJsonObject("wrong") { put("type", "STRING") }
                                    putJsonObject("right") { put("type", "STRING") }
                                    putJsonObject("explanation") { put("type", "STRING") }
                                }
                                putJsonArray("required") { add("type"); add("wrong"); add("right"); add("explanation") }
                            }
                        }
                        putJsonObject("alternatives") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
                        putJsonObject("note") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("lang"); add("verdict"); add("corrected"); add("errors") }
                }
            }
        }
        putJsonArray("required") { add("results") }
    }

    private fun schemaTranslate() = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("translations") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("lang") { put("type", "STRING") }
                        putJsonObject("text") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("lang"); add("text") }
                }
            }
        }
        putJsonArray("required") { add("translations") }
    }

    data class AnswerInput(val lang: Lang, val answer: String, val references: List<String>)

    /** Grades all answers of one round in a single request. */
    suspend fun grade(
        apiKey: String,
        model: String,
        promptLang: Lang,
        prompt: String,
        answers: List<AnswerInput>,
        strictAccents: Boolean,
        strictTones: Boolean,
    ): Map<Lang, LangFeedback> {
        val user = buildString {
            appendLine("Sentence to translate (${promptLang.englishName}): $prompt")
            appendLine("Accents required: ${if (strictAccents) "yes" else "no"}. Tones required: ${if (strictTones) "yes" else "no"}.")
            appendLine()
            answers.forEach { a ->
                appendLine("lang: ${a.lang.code} (${a.lang.englishName})")
                if (a.references.isNotEmpty()) appendLine("reference translations: " + a.references.joinToString(" | "))
                appendLine("learner answer: \"${a.answer}\"")
                appendLine()
            }
            append("Return one result per lang code above.")
        }
        val out = call(apiKey, model, user, schemaGrade())
        val results = out["results"]?.jsonArray ?: throw GeminiException("Unexpected response")
        val byLang = HashMap<Lang, LangFeedback>()
        for (r in results.map { it.jsonObject }) {
            val lang = Lang.fromCode(r.str("lang")?.lowercase()?.take(2) ?: "") ?: continue
            val input = answers.firstOrNull { it.lang == lang } ?: continue
            val verdict = when (r.str("verdict")) {
                "correct" -> Verdict.CORRECT
                "acceptable" -> Verdict.ACCEPTABLE
                else -> Verdict.INCORRECT
            }
            byLang[lang] = LangFeedback(
                lang = lang, answer = input.answer, verdict = verdict,
                corrected = r.str("corrected"),
                errors = (r["errors"] as? JsonArray)?.map { it.jsonObject }?.map {
                    GradeError(it.str("type") ?: "other", it.str("wrong") ?: "", it.str("right") ?: "", it.str("explanation") ?: "")
                } ?: emptyList(),
                alternatives = (r["alternatives"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                note = r.str("note")?.ifBlank { null },
                reference = input.references.firstOrNull(),
                byAi = true,
            )
        }
        return byLang
    }

    /** Writes reference translations for languages Tatoeba doesn't cover. */
    suspend fun translate(apiKey: String, model: String, source: Lang, text: String, targets: List<Lang>): Map<Lang, String> {
        val user = buildString {
            appendLine("Translate this ${source.englishName} text naturally and accurately, in a neutral register. Keep line breaks.")
            appendLine("Text: $text")
            appendLine("Target lang codes: " + targets.joinToString(", ") { "${it.code} (${it.englishName}${if (it == Lang.ZH) ", simplified characters" else ""})" })
            append("For Spanish use neutral Latin American or Peninsular Spanish consistently; for Mandarin keep it simple (HSK 1–3 vocabulary where possible).")
        }
        val out = call(apiKey, model, user, schemaTranslate(), system = "You are a professional translator. Return only the translations.")
        return (out["translations"] as? JsonArray)?.map { it.jsonObject }?.mapNotNull { o ->
            val l = Lang.fromCode(o.str("lang")?.lowercase()?.take(2) ?: "") ?: return@mapNotNull null
            val t = o.str("text")?.trim().orEmpty()
            if (t.isEmpty()) null else l to t
        }?.toMap() ?: emptyMap()
    }

    /**
     * Dictionary-style translations of one English word in one meaning, for languages the dictionary
     * has nothing for: nouns with their article, verbs in the infinitive, Mandarin in simplified characters.
     */
    suspend fun suggestWord(apiKey: String, model: String, en: String, pos: String?, meaning: String, targets: List<Lang>): Map<Lang, String> {
        val user = buildString {
            appendLine("English word: \"$en\"" + (pos?.let { " ($it)" } ?: "") + (if (meaning.isNotBlank()) ", meaning: $meaning" else "") + ".")
            appendLine("Give the usual dictionary equivalent in each language: one word or a short fixed expression, as a bilingual dictionary would list it.")
            appendLine("Nouns with their definite article (der/die/das, le/la/l', il/lo/la/l', el/la); verbs in the infinitive; Mandarin in simplified characters.")
            append("Target lang codes: " + targets.joinToString(", ") { "${it.code} (${it.englishName})" })
        }
        val out = call(apiKey, model, user, schemaTranslate(), system = "You are a lexicographer writing a bilingual dictionary. Return only the words.")
        return (out["translations"] as? JsonArray)?.map { it.jsonObject }?.mapNotNull { o ->
            val l = Lang.fromCode(o.str("lang")?.lowercase()?.take(2) ?: "") ?: return@mapNotNull null
            o.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { l to it }
        }?.toMap() ?: emptyMap()
    }

    /** A generated parallel story: titles and sentences, aligned 1:1 across languages. */
    data class StoryDraft(val titles: Map<Lang, String>, val sentences: List<Map<Lang, String>>)

    private fun langObjectSchema(langs: List<Lang>) = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") { langs.forEach { l -> putJsonObject(l.code) { put("type", "STRING") } } }
        putJsonArray("required") { langs.forEach { add(it.code) } }
    }

    /**
     * Writes a short graded story and its translations into [langs], sentence by sentence.
     * European languages at [levelEu] (A2/B1/B2), Mandarin at [hsk] (HSK level), simplified characters.
     */
    suspend fun generateStory(
        apiKey: String, model: String, topic: String, levelEu: String, hsk: Int, langs: List<Lang>, sentences: Int = 14,
    ): StoryDraft {
        val schema = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                put("title", langObjectSchema(langs))
                putJsonObject("sentences") { put("type", "ARRAY"); put("items", langObjectSchema(langs)) }
            }
            putJsonArray("required") { add("title"); add("sentences") }
        }
        val user = buildString {
            appendLine("Write an original short story for language learners. Topic: $topic.")
            appendLine("Length: about $sentences sentences, with a clear beginning, middle and end, and a little dialogue.")
            appendLine("Write it in all these languages: " + langs.joinToString { "${it.code} (${it.englishName})" } + ".")
            appendLine("Each item of \"sentences\" is ONE sentence and its translations, so the languages line up sentence by sentence.")
            appendLine("Translations must be natural, not word-for-word, but keep the same meaning and the same sentence boundaries.")
            appendLine("Level: $levelEu (CEFR) for English, French, German, Italian and Spanish; HSK $hsk vocabulary and grammar for Mandarin, in simplified characters with Chinese punctuation.")
            append("Avoid rare words; prefer everyday vocabulary and clear grammar typical of the level.")
        }
        val out = call(apiKey, model, user, schema, system = "You are a writer of graded readers for language learners. Return only the story as JSON.")
        fun langMap(o: JsonObject): Map<Lang, String> =
            langs.mapNotNull { l -> o.str(l.code)?.trim()?.takeIf { it.isNotEmpty() }?.let { l to it } }.toMap()
        val titles = (out["title"] as? JsonObject)?.let(::langMap) ?: emptyMap()
        val items = (out["sentences"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::langMap) }?.filter { it.size == langs.size }
            ?: emptyList()
        if (items.isEmpty()) throw GeminiException("Gemini returned an empty story. Try again.")
        return StoryDraft(titles, items)
    }

    /** One generated exercise: the sentence in every language, plus a short note (situation or grammar point). */
    data class Exercise(val texts: Map<Lang, String>, val note: String?)

    enum class ExerciseKind { GRAMMAR, CONVERSATION, IDIOM }

    /**
     * Writes one new practice sentence in all [langs], aligned in meaning.
     * GRAMMAR: built so that its translations need [focus]. CONVERSATION: a natural spoken line, with the situation as the note.
     * IDIOM: a common idiom or saying, each language using its own natural equivalent.
     * [avoid] lists recent sentences so the same one isn't repeated.
     */
    suspend fun generateExercise(
        apiKey: String, model: String, kind: ExerciseKind, focus: String?, levelEu: String, hsk: Int,
        langs: List<Lang>, avoid: List<String> = emptyList(),
    ): Exercise {
        val schema = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                put("sentence", langObjectSchema(langs))
                putJsonObject("note") { put("type", "STRING") }
            }
            putJsonArray("required") { add("sentence"); add("note") }
        }
        val user = buildString {
            when (kind) {
                ExerciseKind.GRAMMAR -> {
                    appendLine("Write ONE new sentence for translation practice that makes the learner use: $focus.")
                    appendLine("The sentence must require that structure in the languages where it exists, and sound natural in every language.")
                    appendLine("\"note\": one short English sentence naming the structure the learner should use (no full answer).")
                }
                ExerciseKind.CONVERSATION -> {
                    appendLine("Write ONE natural line someone would actually say in an everyday conversation (shop, travel, work, friends, family, phone call, doctor…). Vary the situation.")
                    appendLine("Use spoken register: contractions, common expressions, polite forms where natural.")
                    appendLine("\"note\": the situation in one short English sentence, e.g. \"At a pharmacy, asking for something for a cough.\"")
                }
                ExerciseKind.IDIOM -> {
                    appendLine("Pick ONE common idiom, proverb or set expression and write a short sentence using it.")
                    appendLine("In each language use that language's own natural equivalent idiom (e.g. 'it's raining cats and dogs' → 'il pleut des cordes'); for Mandarin a common 成语 or saying where one fits.")
                    appendLine("\"note\": the meaning of the idiom in one short English sentence.")
                }
            }
            appendLine("Languages: " + langs.joinToString { "${it.code} (${it.englishName})" } + ". Fill every language.")
            appendLine("Level: $levelEu (CEFR) for the European languages; HSK $hsk for Mandarin, in simplified characters with Chinese punctuation.")
            appendLine("Keep it to one sentence of at most 20 words.")
            if (avoid.isNotEmpty()) append("Do not reuse any of these: " + avoid.takeLast(15).joinToString(" | "))
        }
        val out = call(apiKey, model, user, schema, system = "You write practice sentences for language learners. Return only JSON.")
        val texts = (out["sentence"] as? JsonObject)?.let { o ->
            langs.mapNotNull { l -> o.str(l.code)?.trim()?.takeIf { it.isNotEmpty() }?.let { l to it } }.toMap()
        } ?: emptyMap()
        if (texts.size < 2) throw GeminiException("Gemini didn't return a usable sentence. Try again.")
        return Exercise(texts, out.str("note")?.trim()?.ifEmpty { null })
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    /** One request with a JSON response [schema], for features that build their own prompt (Tests). */
    suspend fun generateJson(apiKey: String, model: String, userText: String, schema: JsonObject, system: String): JsonObject =
        call(apiKey, model, userText, schema, system)

    /** Calls generateContent; on "model not found" tries the fallback models. */
    private suspend fun call(apiKey: String, model: String, userText: String, schema: JsonObject, system: String = SYSTEM): JsonObject {
        if (apiKey.isBlank()) throw GeminiException("No Gemini API key set.")
        val models = (listOf(model) + FALLBACK_MODELS).filter { it.isNotBlank() }.distinct()
        var lastError: GeminiException? = null
        for (m in models) {
            try {
                return callOnce(apiKey, m, userText, schema, system)
            } catch (e: ModelUnavailable) {
                lastError = GeminiException("Model $m is not available.")
            }
        }
        throw lastError ?: GeminiException("No model available.")
    }

    private class ModelUnavailable : Exception()

    private suspend fun callOnce(apiKey: String, model: String, userText: String, schema: JsonObject, system: String): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", userText) }) }
                    })
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                    put("temperature", 0.2)
                }
            }
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10_000; readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
                when {
                    code == 404 -> throw ModelUnavailable()
                    code == 400 && text.contains("API key", ignoreCase = true) -> throw GeminiException("The Gemini API key was rejected. Check it in Settings.")
                    code == 401 || code == 403 -> throw GeminiException("The Gemini API key was rejected. Check it in Settings.")
                    code == 429 -> throw GeminiException("Gemini's free-tier limit was reached. Try again in a minute.")
                    code !in 200..299 -> throw GeminiException("Gemini error $code.")
                }
                val root = json.parseToJsonElement(text).jsonObject
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: throw GeminiException("Gemini returned no answer.")
                val out = parts.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }.joinToString("")
                json.parseToJsonElement(out).jsonObject
            } finally {
                conn.disconnect()
            }
        }
}
