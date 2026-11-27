package app.diacritica.rehearse

import app.diacritica.data.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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

    /** A model choice for Settings. */
    data class ModelInfo(
        val id: String, val name: String, val bestFor: String,
        val speed: Int, val quality: Int, val freePerDay: String, val recommended: Boolean = false,
    )

    companion object {
        /**
         * Tried in order when the chosen model is unavailable (renamed or retired) or has used up its
         * free daily requests. Flash-Lite models have by far the largest free allowance.
         */
        val FALLBACK_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash")
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        /** Models offered in Settings: (id, label, note). */
        /**
         * Models offered in Settings. Speed and quality are 1–3, relative to each other; free allowances
         * are approximate (Google doesn't publish them and changes them; AI Studio shows yours).
         */
        val MODELS = listOf(
            ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Everyday use: grading, sentences, stories",
                speed = 3, quality = 2, freePerDay = "about 500", recommended = true),
            ModelInfo("gemini-3.6-flash", "Gemini 3.6 Flash", "Sharper corrections and lessons for tests",
                speed = 2, quality = 3, freePerDay = "about 20"),
            ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", "Newest model; strongest explanations",
                speed = 2, quality = 3, freePerDay = "about 20"),
        )

        /** Retired IDs that Google forwards to another model (and counts there): use the default instead. */
        val RETIRED = setOf("gemini-3.5-flash", "gemini-3.7-flash", "gemini-2.5-flash", "gemini-2.0-flash")

        /** Models that hit their free daily limit, with the Pacific date it happened (quotas reset at midnight PT). */
        private val exhausted = java.util.concurrent.ConcurrentHashMap<String, String>()
        private fun pacificToday(): String =
            java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).toString()

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

    /**
     * GRAMMAR / CONVERSATION / IDIOM are the Gemini paths. PASSAGE writes a short connected text in the style
     * given as `focus` (everyday life, a news report…) for the sentence-bank paths when you ask for 2–3 sentences.
     */
    enum class ExerciseKind { GRAMMAR, CONVERSATION, IDIOM, PASSAGE }

    /**
     * Writes one new practice sentence in all [langs], aligned in meaning.
     * GRAMMAR: built so that its translations need [focus]. CONVERSATION: a natural spoken line, with the situation as the note.
     * IDIOM: a common idiom or saying, each language using its own natural equivalent.
     * [avoid] lists recent sentences so the same one isn't repeated.
     */
    suspend fun generateExercise(
        apiKey: String, model: String, kind: ExerciseKind, focus: String?, levelEu: String, hsk: Int,
        langs: List<Lang>, avoid: List<String> = emptyList(), sentences: Int = 1,
    ): Exercise {
        val n = sentences.coerceIn(1, 3)
        val unit = if (n == 1) "ONE sentence" else "a short connected passage of exactly $n sentences (one small scene, story or explanation)"
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
                    appendLine("Write $unit for translation practice that makes the learner use: $focus.")
                    appendLine("It must require that structure (at least once) in the languages where it exists, and sound natural in every language.")
                    appendLine("\"note\": one short English sentence naming the structure the learner should use (no full answer).")
                }
                ExerciseKind.CONVERSATION -> {
                    appendLine(if (n == 1) "Write ONE natural line someone would actually say in an everyday conversation (shop, travel, work, friends, family, phone call, doctor…). Vary the situation."
                        else "Write $n natural lines one person would say in a row in an everyday conversation (shop, travel, work, friends, family, phone call, doctor…). Vary the situation.")
                    appendLine("Use spoken register: contractions, common expressions, polite forms where natural.")
                    appendLine("\"note\": the situation in one short English sentence, e.g. \"At a pharmacy, asking for something for a cough.\"")
                }
                ExerciseKind.IDIOM -> {
                    appendLine("Pick ONE common idiom, proverb or set expression and write " + (if (n == 1) "a short sentence" else "$unit") + " using it.")
                    appendLine("In each language use that language's own natural equivalent idiom (e.g. 'it's raining cats and dogs' → 'il pleut des cordes'); for Mandarin a common 成语 or saying where one fits.")
                    appendLine("\"note\": the meaning of the idiom in one short English sentence.")
                }
                ExerciseKind.PASSAGE -> {
                    appendLine("Write $unit for translation practice: $focus.")
                    appendLine("Natural, idiomatic text a native speaker would write; human-translation quality in every language.")
                    appendLine("\"note\": empty string.")
                }
            }
            appendLine("Languages: " + langs.joinToString { "${it.code} (${it.englishName})" } + ". Fill every language.")
            appendLine("Level: $levelEu (CEFR) for the European languages; HSK $hsk for Mandarin, in simplified characters with Chinese punctuation.")
            appendLine(if (n == 1) "Keep it to one sentence of at most 20 words." else "Exactly $n sentences, at most 18 words each; each language's text is the whole passage.")
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

    /**
     * Up to [max] grammar points worth a lesson in a text the learner is translating, about [lang] (the text's
     * own language), most useful first. Explanations in English.
     */
    suspend fun grammarNotes(apiKey: String, model: String, lang: Lang, text: String, max: Int = 3): List<app.diacritica.mybooks.GrammarPoint> {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("points") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("pattern") { put("type", "string") }
                            putJsonObject("inText") { put("type", "string") }
                            putJsonObject("explanation") { put("type", "string") }
                        }
                        putJsonArray("required") { add("title"); add("pattern"); add("inText"); add("explanation") }
                    }
                }
            }
            putJsonArray("required") { add("points") }
        }
        val user = buildString {
            appendLine("This ${lang.englishName} text is being translated by a learner of ${lang.englishName}:")
            appendLine(text.take(2000))
            appendLine()
            appendLine("Pick at most $max grammar points of ${lang.englishName} that this text actually uses and that are worth a lesson")
            appendLine("for an intermediate learner (tenses and moods, agreement, cases, word order, particles, aspect, set structures).")
            appendLine("Skip trivial ones. If nothing is worth it, return an empty list.")
            appendLine("title: short name of the point in English. pattern: the structure in ${lang.englishName} (e.g. \u201chaber + participio\u201d,")
            appendLine("\u201c了 after a verb\u201d). inText: the words of the text that show it. explanation: 2–4 plain English sentences.")
        }
        val out = call(apiKey, model, user, schema)
        return (out["points"] as? JsonArray)?.map { it.jsonObject }?.mapNotNull { o ->
            val title = o.str("title")?.ifBlank { null } ?: return@mapNotNull null
            app.diacritica.mybooks.GrammarPoint(
                title = title, pattern = o.str("pattern").orEmpty(), inPassage = o.str("inText").orEmpty(),
                explanation = o.str("explanation").orEmpty(),
            )
        }?.take(max) ?: emptyList()
    }

    /** Gemini's verdict on a sentence the learner wrote with a given word (Review › Use it in a sentence). */
    data class UsageCheck(
        /** "correct", "minor" (small mistakes) or "wrong". */
        val verdict: String,
        val usesWord: Boolean,
        val corrected: String?,
        val explanation: String,
        val natural: String?,
    )

    suspend fun checkUsage(apiKey: String, model: String, lang: Lang, word: String, meaning: String?, sentence: String): UsageCheck {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("verdict") { put("type", "string"); putJsonArray("enum") { add("correct"); add("minor"); add("wrong") } }
                putJsonObject("usesWord") { put("type", "boolean") }
                putJsonObject("corrected") { put("type", "string") }
                putJsonObject("explanation") { put("type", "string") }
                putJsonObject("natural") { put("type", "string") }
            }
            putJsonArray("required") { add("verdict"); add("usesWord"); add("explanation") }
        }
        val user = buildString {
            appendLine("Language: ${lang.englishName}")
            appendLine("Target word: $word" + (meaning?.let { " (meaning: $it)" } ?: ""))
            appendLine("The learner's sentence: \u201c$sentence\u201d")
            appendLine()
            appendLine("Check whether the sentence uses the target word (any inflected form counts) correctly and in a fitting meaning,")
            appendLine("and whether the sentence is grammatical. verdict: correct = natural and right; minor = understandable with small")
            appendLine("mistakes; wrong = the word is misused or missing, or the sentence is badly broken.")
            appendLine("corrected: the learner's sentence with the fewest changes that make it right (empty if already right).")
            appendLine("explanation: 1–3 short sentences in English about what's wrong or why it's good.")
            appendLine("natural: how a native speaker might say the same thing (empty if the learner's is already natural).")
            if (lang == Lang.ZH) appendLine("For Mandarin, accept simplified or traditional characters.")
        }
        val out = call(apiKey, model, user, schema)
        return UsageCheck(
            verdict = out.str("verdict") ?: "minor",
            usesWord = (out["usesWord"] as? JsonPrimitive)?.contentOrNull != "false",
            corrected = out.str("corrected")?.ifBlank { null },
            explanation = out.str("explanation").orEmpty(),
            natural = out.str("natural")?.ifBlank { null },
        )
    }

    /** A short everyday sentence using [word] in [lang], with its translation into [into] (Review › Translate a sentence). */
    suspend fun sentenceWith(apiKey: String, model: String, lang: Lang, word: String, meaning: String?, into: Lang, hsk: Int?): Pair<String, String> {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("sentence") { put("type", "string") }
                putJsonObject("translation") { put("type", "string") }
            }
            putJsonArray("required") { add("sentence"); add("translation") }
        }
        val user = buildString {
            appendLine("Write one short, natural, everyday ${lang.englishName} sentence (6–14 words) that uses \u201c$word\u201d" + (meaning?.let { " in the meaning \u201c$it\u201d" } ?: "") + ".")
            if (lang == Lang.ZH) appendLine("Keep the other words at about HSK ${hsk ?: 3} level. Simplified characters.")
            else appendLine("Keep the other words at about B1 level.")
            appendLine("Then translate it naturally into ${into.englishName}.")
        }
        val out = call(apiKey, model, user, schema)
        return (out.str("sentence") ?: throw GeminiException("Gemini returned no sentence.")) to out.str("translation").orEmpty()
    }

    /** One part of a pronunciation check: a syllable (Mandarin) or a word, and whether it sounded right. */
    data class PronPart(val text: String, val ok: Boolean, val note: String?)

    data class Pronunciation(
        /** 0–100. */
        val score: Int,
        /** "good", "close" or "needs work". */
        val verdict: String,
        /** What Gemini heard. */
        val heard: String,
        val parts: List<PronPart>,
        val tips: List<String>,
    )

    /**
     * Listens to a recording of the learner saying [expected] in [lang] and judges the pronunciation:
     * per syllable for Mandarin (tones above all), per word otherwise, with short tips in English.
     */
    suspend fun pronunciation(apiKey: String, model: String, lang: Lang, expected: String, pinyin: String?, wav: ByteArray): Pronunciation {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("score") { put("type", "integer") }
                putJsonObject("verdict") { put("type", "string"); putJsonArray("enum") { add("good"); add("close"); add("needs work") } }
                putJsonObject("heard") { put("type", "string") }
                putJsonObject("parts") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") { put("type", "string") }
                            putJsonObject("ok") { put("type", "boolean") }
                            putJsonObject("note") { put("type", "string") }
                        }
                        putJsonArray("required") { add("text"); add("ok") }
                    }
                }
                putJsonObject("tips") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            }
            putJsonArray("required") { add("score"); add("verdict"); add("heard"); add("parts"); add("tips") }
        }
        val system = """
            You are a strict but encouraging pronunciation coach. You receive a recording of a language learner saying a
            given word or sentence. Judge only pronunciation, not grammar or word choice. Be honest: if the audio is
            unclear or is a different word, say so. Write notes and tips in short, plain English.
        """.trimIndent()
        val user = buildString {
            appendLine("Language: ${lang.englishName}")
            appendLine("The learner was asked to say: $expected")
            if (lang == Lang.ZH && !pinyin.isNullOrBlank()) appendLine("Pinyin: $pinyin")
            appendLine()
            if (lang == Lang.ZH) {
                appendLine("Give one part per syllable (text = the character with its pinyin, e.g. '电 diàn'). ok = initial, final and tone")
                appendLine("all right. In note, name the problem, especially the tone you heard (e.g. 'said 2nd tone, should be 4th').")
                appendLine("Account for normal tone sandhi (3rd+3rd, 不 and 一) and neutral tones.")
            } else {
                appendLine("Give one part per word. ok = clearly pronounced as a native speaker would accept. In note, name the")
                appendLine("problem sound (e.g. French nasal vowel, German ü, Italian double consonant, silent letters).")
            }
            appendLine("score: 0–100 overall. heard: what you actually heard, written in the language. tips: at most 3, most useful first.")
        }
        val out = call(apiKey, model, user, schema, system, inline = "audio/wav" to wav)
        return Pronunciation(
            score = (out["score"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
            verdict = out.str("verdict") ?: "close",
            heard = out.str("heard").orEmpty(),
            parts = (out["parts"] as? JsonArray)?.map { it.jsonObject }?.map {
                PronPart(it.str("text").orEmpty(), (it["ok"] as? JsonPrimitive)?.contentOrNull == "true", it.str("note")?.ifBlank { null })
            } ?: emptyList(),
            tips = (out["tips"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(3) ?: emptyList(),
        )
    }

    /** One request with a JSON response [schema], for features that build their own prompt (Tests). */
    suspend fun generateJson(apiKey: String, model: String, userText: String, schema: JsonObject, system: String): JsonObject =
        call(apiKey, model, userText, schema, system)

    /** One turn of a conversation: [role] is "user" or "model". */
    data class Turn(val role: String, val text: String)

    /**
     * A free-text reply to a conversation (the Ask chat). Consecutive turns of the same role are merged, since
     * Gemini expects user and model turns to alternate. With a [schema] the reply is JSON (as text).
     */
    suspend fun chat(apiKey: String, model: String, system: String, turns: List<Turn>, schema: JsonObject? = null): String {
        val merged = mutableListOf<Turn>()
        turns.filter { it.text.isNotBlank() }.forEach { t ->
            val last = merged.lastOrNull()
            if (last != null && last.role == t.role) merged[merged.lastIndex] = Turn(t.role, last.text + "\n\n" + t.text) else merged += t
        }
        if (merged.isEmpty() || merged.last().role != "user") throw GeminiException("Nothing to send.")
        val contents = buildJsonArray {
            merged.forEach { t ->
                add(buildJsonObject {
                    put("role", t.role)
                    putJsonArray("parts") { add(buildJsonObject { put("text", t.text) }) }
                })
            }
        }
        val out = callText(apiKey, model, system, contents, schema).trim()
        if (out.isEmpty()) throw GeminiException("Gemini returned an empty answer. Try again.")
        return out
    }

    private fun userContents(userText: String, inline: Pair<String, ByteArray>?): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                add(buildJsonObject { put("text", userText) })
                if (inline != null) add(buildJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", inline.first)
                        put("data", java.util.Base64.getEncoder().encodeToString(inline.second))
                    }
                })
            }
        })
    }

    /** A request with a JSON response [schema]; see [callText] for retries and fallbacks. */
    private suspend fun call(
        apiKey: String, model: String, userText: String, schema: JsonObject, system: String = SYSTEM,
        /** Optional media sent with the text: MIME type and bytes (e.g. "audio/wav"). */
        inline: Pair<String, ByteArray>? = null,
    ): JsonObject = json.parseToJsonElement(callText(apiKey, model, system, userContents(userText, inline), schema)).jsonObject

    /**
     * Calls generateContent and returns the answer's text (JSON when [schema] is given). A busy model
     * (500/503/504, timeouts) is retried twice with a short wait, then the fallback models are tried;
     * "model not found" and quota limits move straight on to the next model.
     */
    private suspend fun callText(apiKey: String, model: String, system: String, contents: JsonArray, schema: JsonObject?): String {
        if (apiKey.isBlank()) throw GeminiException("No Gemini API key set.")
        val today = pacificToday()
        val chosen = model.takeIf { it.isNotBlank() && it !in RETIRED } ?: DEFAULT_MODEL
        val models = (listOf(chosen) + FALLBACK_MODELS).distinct().filter { exhausted[it] != today }
        if (models.isEmpty()) throw GeminiException("Gemini's free daily limit was reached on every model. It resets at 1–2 a.m. (midnight Pacific time).")
        var lastError: GeminiException? = null
        for (m in models) {
            try {
                return withBusyRetries { callOnce(apiKey, m, system, contents, schema) }
            } catch (e: Busy) {
                lastError = GeminiException("Gemini is busy right now (Google's servers are overloaded). Try again in a moment.")
            } catch (e: ModelUnavailable) {
                lastError = GeminiException("Model $m is not available.")
            } catch (e: QuotaExceeded) {
                // Per-minute limits pass in a minute; daily ones until midnight Pacific. Either way, try the next model.
                if (e.daily) exhausted[m] = today
                lastError = GeminiException(
                    if (e.daily) "Gemini's free daily limit was reached. It resets at 1–2 a.m. (midnight Pacific time)."
                    else "Gemini's free per-minute limit was reached. Try again in a minute.",
                )
            }
        }
        throw lastError ?: GeminiException("No model available.")
    }

    /** Runs [block], retrying twice (after ~1.5 s, then ~4 s) while Gemini answers that it's busy. */
    private suspend fun <T> withBusyRetries(block: suspend () -> T): T {
        val waits = longArrayOf(1_500, 4_000)
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Busy) {
                if (attempt >= waits.size) throw e
                kotlinx.coroutines.delay(waits[attempt++])
            }
        }
    }

    private class Busy : Exception()
    private class ModelUnavailable : Exception()
    private class QuotaExceeded(val daily: Boolean) : Exception()

    private suspend fun callOnce(apiKey: String, model: String, system: String, contents: JsonArray, schema: JsonObject?): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
                put("contents", contents)
                // No temperature: Gemini 3.6+ ignores sampling parameters and later models reject them.
                if (schema != null) putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
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
                val code = try {
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    conn.responseCode
                } catch (e: java.net.SocketTimeoutException) {
                    throw Busy()
                }
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
                when {
                    code == 404 -> throw ModelUnavailable()
                    // 503 UNAVAILABLE / "model is overloaded", and other temporary server errors.
                    code == 500 || code == 502 || code == 503 || code == 504 -> throw Busy()
                    code == 400 && text.contains("API key", ignoreCase = true) -> throw GeminiException("The Gemini API key was rejected. Check it in Settings.")
                    code == 401 || code == 403 -> throw GeminiException("The Gemini API key was rejected. Check it in Settings.")
                    code == 429 -> throw QuotaExceeded(daily = text.contains("PerDay", ignoreCase = true) || text.contains("per day", ignoreCase = true))
                    code !in 200..299 -> throw GeminiException("Gemini error $code.")
                }
                val root = json.parseToJsonElement(text).jsonObject
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: throw GeminiException("Gemini returned no answer.")
                // Thinking models may add "thought" parts: only the answer's text is kept.
                parts.map { it.jsonObject }
                    .filter { (it["thought"] as? JsonPrimitive)?.contentOrNull != "true" }
                    .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }.joinToString("")
            } finally {
                conn.disconnect()
            }
        }
}
