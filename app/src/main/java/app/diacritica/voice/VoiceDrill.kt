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
    /** Question types mixed in a session (at least one). */
    val modes: List<VoiceMode> = listOf(VoiceMode.SAY),
    val source: VoiceSource = VoiceSource.VOCAB,
    /** Your languages: questions are asked in them (Say it) and answered in them (Listen); they take turns. */
    val bases: List<Lang> = listOf(Lang.EN),
    /** Languages to practise; empty = all active ones except English and Spanish. May overlap with [bases]. */
    val targets: List<Lang> = emptyList(),
    /** Number of questions; [ENDLESS] = keep going until you end it. */
    val length: Int = 10,
    /** Vocabulary set id, or null for all sets. */
    val setId: String? = null,
    /** Answers update Review cards that are due (saved words only). */
    val countInReview: Boolean = false,
    /** Go on to the next question by itself after the feedback. */
    val autoNext: Boolean = true,
) {
    val endless: Boolean get() = length == ENDLESS

    fun basesFor(active: List<Lang>): List<Lang> = bases.filter { it in active }.ifEmpty { listOf(active.firstOrNull() ?: Lang.EN) }

    /**
     * The languages you answer in (Say it) or hear (Listen). A language can be one of your languages and one
     * you practise at the same time: questions just never ask and answer in the same one.
     */
    fun targetsFor(active: List<Lang>): List<Lang> =
        targets.filter { it in active }.ifEmpty { active.filter { it != Lang.EN && it != Lang.ES } }.ifEmpty { active }

    /** Every (question language, answer language) pair the setup allows. */
    fun pairsFor(active: List<Lang>): List<Pair<Lang, Lang>> =
        basesFor(active).flatMap { b -> targetsFor(active).filter { it != b }.map { t -> b to t } }

    fun encode(): String = listOf(
        "modes=${modes.joinToString(",") { it.name }}", "source=${source.name}", "bases=${bases.joinToString(",") { it.code }}",
        "targets=${targets.joinToString(",") { it.code }}", "length=$length", "set=${setId.orEmpty()}",
        "review=$countInReview", "auto=$autoNext",
    ).joinToString(";")

    companion object {
        const val ENDLESS = 0

        fun decode(s: String?): VoiceConfig {
            if (s.isNullOrBlank()) return VoiceConfig()
            val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = VoiceConfig()
            // Older setups stored one "mode" and one "base".
            val modes = (m["modes"] ?: m["mode"]).orEmpty().split(',').mapNotNull { v -> VoiceMode.entries.firstOrNull { it.name == v } }
            val bases = (m["bases"] ?: m["base"]).orEmpty().split(',').mapNotNull { Lang.fromCode(it) }
            return VoiceConfig(
                modes = modes.ifEmpty { d.modes },
                source = VoiceSource.entries.firstOrNull { it.name == m["source"] } ?: d.source,
                bases = bases.ifEmpty { d.bases },
                targets = m["targets"].orEmpty().split(',').mapNotNull { Lang.fromCode(it) },
                length = m["length"]?.toIntOrNull()?.let { if (it == ENDLESS) ENDLESS else it.coerceIn(5, 100) } ?: d.length,
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
    /** The learner's language this question uses (frames are spoken in it when it's Spanish). */
    val base: Lang,
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

enum class VoiceVerdict(val label: String) { RIGHT("Right"), CLOSE("Almost"), WRONG("Not quite"), NOT_HEARD("Didn't hear you"), SKIPPED("Skipped") }

/** Things you can say instead of an answer, so the drill works without touching the phone. */
enum class VoiceCommand { REPEAT, SLOWER, SKIP, STOP }

object VoiceCommands {
    private fun norm(s: String) = TextNorm.normalize(s).replace("ß", "ss").replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val words: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.REPEAT to listOf(
            "repeat", "repeat please", "repeat that", "again", "say it again", "one more time",
            "repite", "repitelo", "repite por favor", "otra vez", "de nuevo",
            "repete", "repeter", "encore une fois", "repete s il te plait",
            "wiederholen", "wiederhole", "noch mal", "nochmal", "noch einmal", "bitte wiederholen",
            "ripeti", "ripetere", "di nuovo", "ancora una volta",
            "再说一遍", "再说一次", "重复", "再来一遍", "再一遍",
        ),
        VoiceCommand.SLOWER to listOf(
            "slower", "more slowly", "slowly", "slower please",
            "mas despacio", "despacio", "mas lento",
            "plus lentement", "lentement", "moins vite",
            "langsamer", "langsam", "bitte langsamer",
            "piu lentamente", "piu piano",
            "慢一点", "慢点", "慢一点儿", "说慢点", "慢慢说",
        ),
        VoiceCommand.SKIP to listOf(
            "skip", "next", "i don t know", "i dont know", "no idea",
            "siguiente", "saltar", "no se", "no lo se", "ni idea",
            "suivant", "passe", "je ne sais pas", "je sais pas",
            "weiter", "nachste", "naechste", "uberspringen", "ich weiss nicht", "keine ahnung", "weiss nicht",
            "avanti", "prossima", "prossimo", "non lo so", "non so",
            "下一个", "跳过", "不知道", "我不知道",
        ),
        VoiceCommand.STOP to listOf(
            "stop", "stop the drill", "finish", "quit",
            "detente", "terminar", "termina", "basta",
            "arrete", "terminer",
            "stopp", "aufhoren", "aufhoeren", "beenden", "schluss",
            "ferma", "fermati",
            "停止", "结束",
        ),
    ).mapValues { (_, v) -> v.map { norm(it) } }

    /**
     * The command in what was heard, if the whole utterance is one. An utterance that is also a right answer
     * ("siguiente" when asked for "next" in Spanish) counts as the answer, not a command.
     */
    fun detect(item: VoiceItem, heard: List<String>): VoiceCommand? {
        if (heard.isEmpty()) return null
        if (VoiceCheck.check(item, heard).verdict == VoiceVerdict.RIGHT) return null
        for (h in heard.take(3)) {
            val n = norm(h).removeSuffix(" please").removePrefix("please ")
            for ((cmd, list) in words) if (n in list) return cmd
        }
        return null
    }
}

/** The spoken parts of a question and of the feedback, in your language when it's English or Spanish. */
object VoicePhrases {
    /** The phrases of one language: how a question and the feedback are said in it. */
    private class Frames(
        val names: Map<Lang, String>,
        /** "How do you say {x} in {L}?" */
        val say: (x: String, l: String) -> String,
        /** "Listen, and say it in {L}." */
        val listen: (l: String) -> String,
        val repeat: String,
        val right: String, val close: String, val wrong: String, val notHeard: String,
        /** Said before repeating a question. */
        val again: String = "Again.",
        /** Said when you skip. */
        val skipped: String = "Skipped. It's",
    )

    private fun names(en: String, fr: String, de: String, it: String, es: String, zh: String) =
        mapOf(Lang.EN to en, Lang.FR to fr, Lang.DE to de, Lang.IT to it, Lang.ES to es, Lang.ZH to zh)

    private val frames: Map<Lang, Frames> = mapOf(
        Lang.EN to Frames(
            names("English", "French", "German", "Italian", "Spanish", "Mandarin"),
            { x, l -> "How do you say $x in $l?" }, { l -> "Listen, and say it in $l." }, "Repeat after me.",
            "Right.", "Almost. It's", "The answer is", "I didn't hear you. It's", "Again.", "Skipped. It's",
        ),
        Lang.ES to Frames(
            names("inglés", "francés", "alemán", "italiano", "español", "chino"),
            { x, l -> "¿Cómo se dice $x en $l?" }, { l -> "Escucha y dilo en $l." }, "Repite.",
            "¡Correcto!", "Casi. Es", "La respuesta es", "No te oí. La respuesta es", "Otra vez.", "Saltada. Es",
        ),
        Lang.FR to Frames(
            names("anglais", "français", "allemand", "italien", "espagnol", "chinois"),
            { x, l -> "Comment dit-on $x en $l ?" }, { l -> "Écoute, et dis-le en $l." }, "Répète.",
            "Exact !", "Presque. C'est", "La réponse est", "Je ne t'ai pas entendu. C'est", "Encore une fois.", "On passe. C'est",
        ),
        Lang.DE to Frames(
            names("Englisch", "Französisch", "Deutsch", "Italienisch", "Spanisch", "Chinesisch"),
            { x, l -> "Wie sagt man $x auf $l?" }, { l -> "Hör zu und sag es auf $l." }, "Sprich nach.",
            "Richtig!", "Fast. Es heißt", "Die Antwort ist", "Ich habe dich nicht gehört. Es heißt", "Noch einmal.", "Übersprungen. Es heißt",
        ),
        Lang.IT to Frames(
            names("inglese", "francese", "tedesco", "italiano", "spagnolo", "cinese"),
            { x, l -> "Come si dice $x in $l?" }, { l -> "Ascolta e dillo in $l." }, "Ripeti.",
            "Giusto!", "Quasi. Si dice", "La risposta è", "Non ti ho sentito. Si dice", "Ancora una volta.", "Saltata. Si dice",
        ),
        Lang.ZH to Frames(
            names("英语", "法语", "德语", "意大利语", "西班牙语", "中文"),
            { x, l -> "“$x”用${l}怎么说？" }, { l -> "请听，然后用${l}说。" }, "请跟我读。",
            "对！", "差不多。应该是", "答案是", "我没听到。答案是", "再说一遍。", "跳过。答案是",
        ),
    )

    private fun f(l: Lang): Frames = frames[l] ?: frames.getValue(Lang.EN)

    /**
     * (text, language) pieces spoken one after the other. The question is spoken in the language it's
     * asked in: a French question sounds fully French ("Comment dit-on la clé en chinois ?").
     */
    fun question(item: VoiceItem): List<Pair<String, Lang>> = when (item.mode) {
        VoiceMode.SAY -> f(item.ask).let { fr -> listOf(fr.say(item.askText, fr.names.getValue(item.answer)) to item.ask) }
        VoiceMode.LISTEN -> f(item.answer).let { fr -> listOf(fr.listen(fr.names.getValue(item.answer)) to item.answer, item.askText to item.ask) }
        VoiceMode.ECHO -> listOf(f(item.ask).repeat to item.ask, item.askText to item.ask)
    }

    /** "Again." in the language the question is spoken in, before repeating it. */
    fun again(item: VoiceItem): Pair<String, Lang> {
        val lang = if (item.mode == VoiceMode.LISTEN) item.answer else item.ask
        return f(lang).again to lang
    }

    /** Feedback in the language the question was asked in, then the answer in its own voice. */
    fun feedback(v: VoiceVerdict, item: VoiceItem): List<Pair<String, Lang>> {
        val lang = if (item.mode == VoiceMode.SAY) item.ask else item.answer
        val fr = f(lang)
        val lead = when (v) {
            VoiceVerdict.RIGHT -> fr.right
            VoiceVerdict.CLOSE -> fr.close
            VoiceVerdict.WRONG -> fr.wrong
            VoiceVerdict.NOT_HEARD -> fr.notHeard
            VoiceVerdict.SKIPPED -> fr.skipped
        }
        return listOf(lead to lang, item.display to item.answer)
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
