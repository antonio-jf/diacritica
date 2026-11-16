package app.diacritica.reader

import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.BreakIterator

/** Turns pasted text into a two-language story: sentence splitting and (Gemini) aligned translation. */
object TextImport {
    const val MAX_CHARS = 8000
    const val MAX_SENTENCES = 150

    /**
     * Sentences of [text] in [lang], in order. Line breaks end a sentence (headings, dialogue lines);
     * fragments under 3 letters join the previous sentence ("Dr." , "…").
     */
    fun sentences(text: String, lang: Lang): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val it = BreakIterator.getSentenceInstance(lang.locale)
            it.setText(para)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                val s = para.substring(start, end).trim()
                if (s.isNotEmpty()) {
                    val letters = s.count { ch -> ch.isLetter() }
                    if (letters < 3 && out.isNotEmpty()) out[out.lastIndex] = out.last() + " " + s else out += s
                }
                start = end
                end = it.next()
            }
            if (out.size >= MAX_SENTENCES) break
        }
        return out.take(MAX_SENTENCES)
    }

    /** A title from the first sentence: up to 6 words. */
    fun titleFrom(sentences: List<String>): String {
        val first = sentences.firstOrNull().orEmpty().trimEnd('.', '!', '?', '。', '！', '？', ':')
        val words = first.split(Regex("\\s+"))
        return if (words.size <= 6) first else words.take(6).joinToString(" ") + "…"
    }

    private fun schema(targets: List<Lang>) = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("sentences") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("i") { put("type", "INTEGER") }
                        targets.forEach { l -> putJsonObject(l.code) { put("type", "STRING") } }
                    }
                    putJsonArray("required") { add("i"); targets.forEach { add(it.code) } }
                }
            }
        }
        putJsonArray("required") { add("sentences") }
    }

    fun prompt(from: Lang, sentences: List<String>, targets: List<Lang>): String = buildString {
        appendLine("Translate each numbered ${from.englishName} sentence into: " + targets.joinToString { "${it.code} (${it.englishName}${if (it == Lang.ZH) ", simplified characters" else ""})" } + ".")
        appendLine("Keep exactly one translation per numbered sentence, in the same order, natural and faithful to context. Return \"i\" as the sentence number.")
        sentences.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
    }

    /** Parses Gemini's answer into one map per sentence (the original included); missing ones are left without that language. */
    fun parse(out: JsonObject, from: Lang, sentences: List<String>, targets: List<Lang>): List<Map<Lang, String>> {
        val rows = (out["sentences"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .associateBy { ((it["i"] as? JsonPrimitive)?.intOrNull ?: 0) - 1 }
        return sentences.mapIndexed { i, s ->
            buildMap {
                put(from, s)
                rows[i]?.let { r -> targets.forEach { l -> (r[l.code] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() }?.let { put(l, it) } } }
            }
        }
    }

    /** One Gemini request for the whole text (to spare the daily quota). */
    suspend fun translateWithGemini(gemini: GeminiClient, apiKey: String, model: String, from: Lang, sentences: List<String>, targets: List<Lang>): List<Map<Lang, String>> {
        val out = gemini.generateJson(apiKey, model, prompt(from, sentences, targets), schema(targets),
            "You are a professional translator producing sentence-aligned parallel texts. Return only JSON.")
        return parse(out, from, sentences, targets)
    }
}
