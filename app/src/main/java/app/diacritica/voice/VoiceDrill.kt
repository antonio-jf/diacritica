package app.diacritica.voice

import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import app.diacritica.ui.components.WordLookup

/** The three kinds of question. */
enum class VoiceMode(val label: String, val blurb: String) {
    SAY("Say it", "You hear a word in your language and say it in the one you're practising."),
    LISTEN("Listen and translate", "You hear a word in the language you're practising and say it in yours."),
    ECHO("Repeat", "You hear a word and repeat it. Good for pronunciation and listening."),
}

/** Where the words come from. */
enum class VoiceSource(val label: String) { SAVED("Saved words"), VOCAB("Vocabulary"), SENTENCES("Sentences") }

/** The drill's setup, remembered between sessions (Settings key `voice_config`). */
data class VoiceConfig(
    val mode: VoiceMode = VoiceMode.SAY,
    val source: VoiceSource = VoiceSource.VOCAB,
    /** Your language: questions are asked in it (Say it) and answered in it (Listen). */
    val base: Lang = Lang.EN,
    /** Languages to practise; empty = all active ones except English and Spanish. */
    val targets: List<Lang> = emptyList(),
    val length: Int = 10,
    /** Vocabulary set id, or null for all sets. */
    val setId: String? = null,
    /** Answers update Review cards that are due (saved words only). */
    val countInReview: Boolean = false,
    /** Go on to the next question by itself after the feedback. */
    val autoNext: Boolean = true,
) {
    fun targetsFor(active: List<Lang>): List<Lang> =
        targets.filter { it in active && it != base }.ifEmpty { active.filter { it != base && it != Lang.EN && it != Lang.ES } }
            .ifEmpty { active.filter { it != base } }

    fun encode(): String = listOf(
        "mode=${mode.name}", "source=${source.name}", "base=${base.code}", "targets=${targets.joinToString(",") { it.code }}",
        "length=$length", "set=${setId.orEmpty()}", "review=$countInReview", "auto=$autoNext",
    ).joinToString(";")

    companion object {
        fun decode(s: String?): VoiceConfig {
            if (s.isNullOrBlank()) return VoiceConfig()
            val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = VoiceConfig()
            return VoiceConfig(
                mode = VoiceMode.entries.firstOrNull { it.name == m["mode"] } ?: d.mode,
                source = VoiceSource.entries.firstOrNull { it.name == m["source"] } ?: d.source,
                base = m["base"]?.let { Lang.fromCode(it) } ?: d.base,
                targets = m["targets"].orEmpty().split(',').mapNotNull { Lang.fromCode(it) },
                length = m["length"]?.toIntOrNull()?.coerceIn(5, 50) ?: d.length,
                setId = m["set"]?.ifBlank { null },
                countInReview = m["review"] == "true",
                autoNext = m["auto"]?.let { it == "true" } ?: d.autoNext,
            )
        }
    }
}

/**
 * One question. [ask] / [askText]: what is spoken as the question. [answer]: the language you answer in;
 * [accepted]: every answer that counts; [display]: the answer shown and spoken back.
 */
data class VoiceItem(
    val mode: VoiceMode,
    val ask: Lang,
    val askText: String,
    val answer: Lang,
    val accepted: List<String>,
    val display: String,
    val pinyin: String?,
    val sentence: Boolean,
    /** The practised word, when it has Review cards (saved words). */
    val review: Pair<Lang, String>? = null,
)

enum class VoiceVerdict(val label: String) { RIGHT("Right"), CLOSE("Almost"), WRONG("Not quite"), NOT_HEARD("Didn't hear you") }

/** The spoken parts of a question and of the feedback, in your language when it's English or Spanish. */
object VoicePhrases {
    private fun es(base: Lang) = base == Lang.ES

    /** The language the frame ("How do you say…") is spoken in. */
    fun frameLang(base: Lang): Lang = if (base == Lang.ES) Lang.ES else Lang.EN

    private fun name(l: Lang, spanish: Boolean): String = if (!spanish) l.englishName else when (l) {
        Lang.EN -> "inglés"; Lang.FR -> "francés"; Lang.DE -> "alemán"; Lang.IT -> "italiano"; Lang.ES -> "español"; Lang.ZH -> "chino"
    }

    /** (text, language) pieces spoken one after the other. */
    fun question(item: VoiceItem, base: Lang): List<Pair<String, Lang>> {
        val f = frameLang(base)
        val sp = es(base)
        return when (item.mode) {
            VoiceMode.SAY -> if (item.ask == f) {
                listOf((if (sp) "¿Cómo se dice ${item.askText} en ${name(item.answer, true)}?" else "How do you say ${item.askText} in ${item.answer.englishName}?") to f)
            } else {
                listOf((if (sp) "En ${name(item.answer, true)}, ¿cómo se dice:" else "In ${item.answer.englishName}, how do you say:") to f, item.askText to item.ask)
            }
            VoiceMode.LISTEN -> listOf(
                (if (sp) "Escucha y dilo en ${name(item.answer, true)}." else "Listen, and say it in ${item.answer.englishName}.") to f,
                item.askText to item.ask,
            )
            VoiceMode.ECHO -> listOf((if (sp) "Repite." else "Repeat after me.") to f, item.askText to item.ask)
        }
    }

    fun feedback(v: VoiceVerdict, item: VoiceItem, base: Lang): List<Pair<String, Lang>> {
        val f = frameLang(base)
        val sp = es(base)
        val lead = when (v) {
            VoiceVerdict.RIGHT -> if (sp) "¡Correcto!" else "Right."
            VoiceVerdict.CLOSE -> if (sp) "Casi. Es" else "Almost. It's"
            VoiceVerdict.WRONG -> if (sp) "La respuesta es" else "The answer is"
            VoiceVerdict.NOT_HEARD -> if (sp) "No te oí. La respuesta es" else "I didn't hear you. It's"
        }
        return listOf(lead to f, item.display to item.answer)
    }
}

/** Compares what was heard with the accepted answers. */
object VoiceCheck {
    data class Result(val verdict: VoiceVerdict, val note: String? = null)

    private fun tokens(s: String, lang: Lang): List<String> {
        val n = TextNorm.normalize(s).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        return if (lang == Lang.ZH) n.filter { !it.isWhitespace() }.map { it.toString() }
        else n.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** The answer without a leading article or the (m)/(f) note: "der Apfel" → "apfel". */
    private fun core(s: String, lang: Lang): List<String> = tokens(WordLookup.clean(s), lang)

    /** Variants written into one answer: "la télé / la télévision", "il computer, il PC". */
    fun variants(answer: String): List<String> =
        answer.split(" / ", ";", "|").flatMap { it.split(", ") }.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Words: right when an answer appears in what was heard (a short frame like "c'est la …" is fine);
     * almost when it's one or two letters off. Sentences: by how many words match, in order.
     * Mandarin: by characters, or, when the recognizer picked other characters, by sound ([heardPinyin]).
     */
    fun check(item: VoiceItem, heard: List<String>, heardPinyin: (String) -> String? = { null }): Result {
        if (heard.isEmpty()) return Result(VoiceVerdict.NOT_HEARD)
        val accepted = item.accepted.flatMap { variants(it) }.ifEmpty { listOf(item.display) }
        val lang = item.answer
        if (item.sentence) {
            var best = 0.0
            for (h in heard) for (a in accepted) best = maxOf(best, similarity(tokens(h, lang), tokens(a, lang)))
            return when {
                best >= 0.9 -> Result(VoiceVerdict.RIGHT)
                best >= 0.65 -> Result(VoiceVerdict.CLOSE, "Most words match; compare with the answer below.")
                else -> Result(VoiceVerdict.WRONG)
            }
        }
        for (h in heard) {
            val ht = core(h, lang)
            for (a in accepted) {
                val at = core(a, lang)
                if (at.isEmpty()) continue
                if (ht == at || (containsRun(ht, at) && ht.size <= at.size + 4)) return Result(VoiceVerdict.RIGHT)
            }
        }
        if (lang == Lang.ZH) {
            val want = item.pinyin?.let { toneless(it) }
            if (!want.isNullOrEmpty()) for (h in heard) {
                val p = heardPinyin(h)?.let { toneless(it) } ?: continue
                if (p == want || p.contains(want)) {
                    return Result(VoiceVerdict.RIGHT, "Matched by sound: I heard “$h”. Tones aren't checked; try Check pronunciation.")
                }
            }
        }
        for (h in heard) for (a in accepted) {
            val x = core(h, lang).joinToString(" ")
            val y = core(a, lang).joinToString(" ")
            if (y.length >= 4 && ratio(x, y) >= 0.75) return Result(VoiceVerdict.CLOSE, "Close: I heard “$h”.")
        }
        return Result(VoiceVerdict.WRONG)
    }

    private fun toneless(p: String) = TextNorm.normalize(p).replace(Regex("[^a-z]"), "")

    private fun containsRun(h: List<String>, a: List<String>): Boolean {
        if (a.size > h.size) return false
        for (i in 0..h.size - a.size) if (h.subList(i, i + a.size) == a) return true
        return false
    }

    /** Words in common, in order (LCS), relative to both lengths. */
    fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return 2.0 * dp[a.size][b.size] / (a.size + b.size)
    }

    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - d[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
