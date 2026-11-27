package app.diacritica.chat

import android.content.SharedPreferences
import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.GrammarTopics
import app.diacritica.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** Text you're asking about: what was selected and where it came from ("Reader", "From “Der Prozess”"…). */
data class Quote(val text: String, val source: String? = null)

/** A lesson or test made from an answer: [ref] is the lesson key or the test id. */
data class Made(val kind: Kind, val title: String, val ref: String, val lang: Lang?) {
    enum class Kind { LESSON, TEST }
}

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val quote: Quote? = null,
    /** Lessons and tests made from this answer. */
    val made: List<Made> = emptyList(),
)

/** A finished (or set-aside) conversation, kept in Ask's history. */
data class ChatThread(val id: Long, val updatedAt: Long, val messages: List<ChatMessage>) {
    val title: String get() = ChatLogic.title(messages)
}

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    /** Earlier conversations, newest first (at most [ChatLogic.MAX_HISTORY]). */
    val past: List<ChatThread> = emptyList(),
    /** Attached to the next question; shown above the input with a ✕. */
    val pendingQuote: Quote? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Answers whose lesson or test is being written, by message id. */
    val making: Map<Long, Made.Kind> = emptyMap(),
    /** Why making a lesson or test from an answer failed, by message id. */
    val makeErrors: Map<Long, String> = emptyMap(),
)

/**
 * The Ask chat: questions about grammar, words or a selected piece of text, answered by Gemini with your key.
 * One conversation at a time, kept on the phone (it survives closing the app) until you start a new one.
 */
class ChatSession(
    private val gemini: GeminiClient,
    private val settings: SettingsRepository,
    private val prefs: SharedPreferences,
    private val lessons: app.diacritica.grammar.LessonLibrary,
    private val tests: app.diacritica.tests.TestStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        ChatState(
            messages = ChatLogic.decode(prefs.getString(KEY, null)),
            past = ChatLogic.decodeThreads(prefs.getString(KEY_HISTORY, null)),
        ),
    )
    /** The open conversation's id (kept when it's reopened from history and continued). */
    private var currentId: Long = prefs.getLong(KEY_ID, 0L).takeIf { it != 0L } ?: System.currentTimeMillis()
    private var currentUpdated: Long = prefs.getLong(KEY_UPDATED, System.currentTimeMillis())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var job: Job? = null

    /** Attaches [text] as the quote for the next question (from a selection, the Reader, another app…). */
    fun attach(text: String, source: String?) {
        val t = text.trim().take(ChatLogic.MAX_QUOTE)
        if (t.isEmpty()) return
        _state.update { it.copy(pendingQuote = Quote(t, source), error = null) }
    }

    fun clearQuote() = _state.update { it.copy(pendingQuote = null) }

    /** Sends [question] (with the pending quote, if any). A quote alone asks for an explanation of it. */
    fun send(question: String) {
        val st = _state.value
        if (st.busy) return
        val q = question.trim()
        if (q.isEmpty() && st.pendingQuote == null) return
        val msg = ChatMessage(nextId(), fromUser = true, text = q, quote = st.pendingQuote)
        _state.update { it.copy(messages = it.messages + msg, pendingQuote = null, error = null) }
        currentUpdated = System.currentTimeMillis()
        save()
        answer()
    }

    /** Asks again after an error (the last question is still there, unanswered). */
    fun retry() {
        if (_state.value.busy || _state.value.messages.lastOrNull()?.fromUser != true) return
        answer()
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(busy = false) }
    }

    /** Starts an empty conversation; the current one (if it has messages) moves to the history. */
    fun newChat() {
        job?.cancel()
        _state.update { ChatState(past = archived(it)) }
        currentId = System.currentTimeMillis()
        currentUpdated = currentId
        save()
    }

    /** Reopens a conversation from the history (the current one takes its place there). */
    fun open(id: Long) {
        val target = _state.value.past.firstOrNull { it.id == id } ?: return
        job?.cancel()
        _state.update { st -> ChatState(messages = target.messages, past = archived(st).filter { it.id != id }, pendingQuote = st.pendingQuote) }
        currentId = target.id
        currentUpdated = target.updatedAt
        save()
    }

    /** Removes a conversation from the history. */
    fun delete(id: Long) {
        _state.update { st -> st.copy(past = st.past.filter { it.id != id }) }
        save()
    }

    /** The history with the current conversation added (if it has messages), newest first, capped. */
    private fun archived(st: ChatState): List<ChatThread> {
        val mine = if (st.messages.isEmpty()) emptyList() else listOf(ChatThread(currentId, currentUpdated, st.messages))
        return (mine + st.past.filter { it.id != currentId }).sortedByDescending { it.updatedAt }.take(ChatLogic.MAX_HISTORY)
    }

    /** Removes the last unanswered question, e.g. to rewrite it after an error. Returns it. */
    fun takeBackLast(): ChatMessage? {
        val last = _state.value.messages.lastOrNull()?.takeIf { it.fromUser } ?: return null
        job?.cancel()
        _state.update { it.copy(messages = it.messages.dropLast(1), pendingQuote = last.quote ?: it.pendingQuote, busy = false, error = null) }
        save()
        return last
    }

    private fun answer() {
        job = scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val s = settings.settings.first()
                if (s.geminiKey.isBlank()) throw GeminiClient.GeminiException("Ask needs your Gemini API key: add it in Settings › Rehearse › Gemini.")
                val reply = gemini.chat(s.geminiKey, s.geminiModel, ChatLogic.system(s.activeLanguages), ChatLogic.turns(_state.value.messages))
                _state.update { it.copy(messages = it.messages + ChatMessage(nextId(), fromUser = false, text = reply), busy = false) }
                save()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e as? GeminiClient.GeminiException)?.message
                    ?: if (e is java.io.IOException) "No connection. Ask needs internet." else (e.message ?: "Something went wrong.")
                _state.update { it.copy(busy = false, error = msg) }
            }
        }
    }

    /**
     * Writes a full lesson (Grammar › Full lessons) or a translation test (Tests › Your tests) on what the
     * answer [answerId] and the question before it are about. Gemini first works out the language and the
     * grammar point, then the lesson or test is written as usual.
     */
    fun make(answerId: Long, kind: Made.Kind) {
        if (answerId in _state.value.making) return
        val messages = _state.value.messages
        val exchange = ChatLogic.exchange(messages, answerId) ?: return
        _state.update { it.copy(making = it.making + (answerId to kind), makeErrors = it.makeErrors - answerId) }
        val threadId = currentId
        scope.launch {
            val result = runCatching {
                val s = settings.settings.first()
                if (s.geminiKey.isBlank()) throw GeminiClient.GeminiException("Lessons and tests are written by Gemini: add your API key in Settings.")
                val langs = s.activeLanguages
                val out = gemini.generateJson(s.geminiKey, s.geminiModel, ChatLogic.planPrompt(exchange, langs), ChatLogic.planSchema(langs), ChatLogic.PLAN_SYSTEM)
                val plan = ChatLogic.parsePlan(out, langs) ?: throw GeminiClient.GeminiException("Couldn't tell which grammar point this is about. Ask a more specific question first.")
                when (kind) {
                    Made.Kind.LESSON -> {
                        val key = lessons.askNow(plan.lesson, plan.lang).getOrThrow()
                        Made(kind, lessons.full.value[key]?.title ?: plan.lesson, key, plan.lang)
                    }
                    Made.Kind.TEST -> {
                        val r = app.diacritica.tests.TestRequest(
                            level = s.storyLevel, hsk = s.storyHsk, length = app.diacritica.tests.SentenceLength.MEDIUM,
                            count = 8, focus = plan.focus, practise = plan.practise,
                        )
                        val t = app.diacritica.tests.TestGenerator.generate(gemini, s.geminiKey, s.geminiModel, r)
                        tests.addGenerated(t)
                        Made(kind, t.title, t.id, plan.lang.takeIf { it != Lang.EN && it != Lang.ES })
                    }
                }
            }
            result.onSuccess { made ->
                fun List<ChatMessage>.withMade() = map { if (it.id == answerId) it.copy(made = it.made + made) else it }
                _state.update { st ->
                    // The conversation may have been swapped for another one meanwhile: the record goes to it either way.
                    if (threadId == currentId) st.copy(messages = st.messages.withMade(), making = st.making - answerId)
                    else st.copy(past = st.past.map { t -> if (t.id == threadId) t.copy(messages = t.messages.withMade()) else t }, making = st.making - answerId)
                }
                save()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = (e as? GeminiClient.GeminiException)?.message
                    ?: if (e is java.io.IOException) "No connection. Connect to write it." else "Couldn't write it. Try again."
                _state.update { if (threadId == currentId) it.copy(making = it.making - answerId, makeErrors = it.makeErrors + (answerId to msg)) else it.copy(making = it.making - answerId) }
            }
        }
    }

    private fun nextId(): Long = (_state.value.messages.maxOfOrNull { it.id } ?: 0L) + 1

    private fun save() {
        prefs.edit()
            .putString(KEY, ChatLogic.encode(_state.value.messages))
            .putString(KEY_HISTORY, ChatLogic.encodeThreads(_state.value.past))
            .putLong(KEY_ID, currentId)
            .putLong(KEY_UPDATED, currentUpdated)
            .apply()
    }

    private companion object {
        const val KEY = "ask_chat"
        const val KEY_HISTORY = "ask_history"
        const val KEY_ID = "ask_chat_id"
        const val KEY_UPDATED = "ask_chat_updated"
    }
}

/** The parts of the chat that don't need Android: prompts, history and storage. */
object ChatLogic {
    /** Longest selection kept as a quote. */
    const val MAX_QUOTE = 4_000
    /** Earlier conversations kept in the history. */
    const val MAX_HISTORY = 10
    /** Messages sent back to Gemini as context (the oldest are dropped). */
    const val HISTORY = 24
    /** What a quote with no question asks. */
    const val DEFAULT_QUESTION = "Explain this to me: what it means, the grammar it uses and anything worth noticing."

    fun system(langs: List<Lang>): String {
        val learning = langs.filter { it != Lang.EN && it != Lang.ES }
        return buildString {
            appendLine("You are a friendly, precise language tutor inside Diacritica, a multilingual dictionary and language-learning app.")
            appendLine("The learner is a native speaker of English and Spanish, at B1–B2 in French, German and Italian, and about HSK 2 in Mandarin.")
            if (learning.isNotEmpty()) appendLine("Languages they're studying in the app now: " + learning.joinToString { it.englishName } + ".")
            appendLine()
            appendLine("- Answer in the language the learner writes their question in (English if unclear).")
            appendLine("- When a quoted text is given, it is what the question is about: refer to its exact words.")
            appendLine("- For grammar, name the rule, say why it applies, and give one or two short examples with a translation.")
            appendLine("- Correct mistakes in sentences the learner wrote, showing the corrected version first.")
            appendLine("- Mandarin: simplified characters, with pinyin (tone marks) for words the learner may not know.")
            appendLine("- Be concise: usually under 180 words. Go longer only when asked or when the question needs it.")
            appendLine("- If something has several correct answers or depends on region or register, say so.")
            appendLine("- Questions not about language: answer briefly and helpfully.")
            append("Formatting: short paragraphs; you may use **bold**, *italics* and simple \"- \" lists. No headings, tables or code blocks.")
        }
    }

    /** What a user message says to Gemini: the quote (if any), then the question. */
    fun userText(m: ChatMessage): String {
        val q = m.text.ifBlank { if (m.quote != null) DEFAULT_QUESTION else "" }
        val quote = m.quote ?: return q
        return buildString {
            append("Quoted text")
            quote.source?.let { append(" (").append(it).append(")") }
            appendLine(":")
            appendLine("\"\"\"")
            appendLine(quote.text)
            appendLine("\"\"\"")
            appendLine()
            append(q)
        }
    }

    fun turns(messages: List<ChatMessage>): List<GeminiClient.Turn> =
        messages.takeLast(HISTORY)
            .dropWhile { !it.fromUser } // the history has to start with a question
            .map { GeminiClient.Turn(if (it.fromUser) "user" else "model", if (it.fromUser) userText(it) else it.text) }

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(messages: List<ChatMessage>): String = buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                put("id", m.id)
                put("user", m.fromUser)
                put("text", m.text)
                m.quote?.let { q -> put("quote", q.text); q.source?.let { put("source", it) } }
                if (m.made.isNotEmpty()) put("made", buildJsonArray {
                    m.made.forEach { x ->
                        add(buildJsonObject {
                            put("kind", x.kind.name); put("title", x.title); put("ref", x.ref)
                            x.lang?.let { put("lang", it.code) }
                        })
                    }
                })
            })
        }
    }.toString()

    const val PLAN_SYSTEM = "You read a conversation from a language-learning app and say precisely which grammar point it is about."

    /** The question before answer [answerId] (with its quote) and the answer, as text; null if there's no such answer. */
    fun exchange(messages: List<ChatMessage>, answerId: Long): String? {
        val i = messages.indexOfFirst { it.id == answerId && !it.fromUser }
        if (i < 0) return null
        val question = messages.subList(0, i).lastOrNull { it.fromUser }
        return buildString {
            question?.let { appendLine("Learner:"); appendLine(userText(it)); appendLine() }
            appendLine("Tutor:")
            append(messages[i].text.take(6_000))
        }
    }

    fun planPrompt(exchange: String, langs: List<Lang>): String = buildString {
        appendLine("Here is part of a conversation between a learner and a tutor:")
        appendLine("<<<")
        appendLine(exchange)
        appendLine(">>>")
        appendLine()
        appendLine("The learner wants a full grammar lesson and/or a translation test on what this is about.")
        appendLine("- \"lang\": the language whose grammar it's about (one of: ${langs.joinToString { it.code }}).")
        appendLine("- \"lesson\": what the lesson should teach, in English, one line naming the structure and the language (e.g. \"German dative after mit, bei and zu\", \"了 vs 过 for past experiences in Mandarin\").")
        appendLine("- \"practise\": what every test sentence must require when translated into that language, in English, one line (e.g. \"the dative after mit, bei or zu in German\").")
        append("- \"focus\": the closest of these keys, or \"mixed\" if none fits: ${GrammarTopics.all.joinToString { it.key }}.")
    }

    fun planSchema(langs: List<Lang>) = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("lang", buildJsonObject { put("type", "STRING"); put("enum", buildJsonArray { langs.forEach { add(JsonPrimitive(it.code)) } }) })
            put("lesson", buildJsonObject { put("type", "STRING") })
            put("practise", buildJsonObject { put("type", "STRING") })
            put("focus", buildJsonObject { put("type", "STRING"); put("enum", buildJsonArray { GrammarTopics.all.forEach { add(JsonPrimitive(it.key)) } }) })
        })
        put("required", buildJsonArray { listOf("lang", "lesson", "practise", "focus").forEach { add(JsonPrimitive(it)) } })
    }

    data class Plan(val lang: Lang, val lesson: String, val practise: String, val focus: String)

    fun parsePlan(o: JsonObject, langs: List<Lang>): Plan? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val lang = langs.firstOrNull { it.code == str("lang") } ?: return null
        val lesson = str("lesson").ifEmpty { return null }
        val focus = str("focus").takeIf { k -> GrammarTopics.all.any { it.key == k } } ?: "mixed"
        return Plan(lang, lesson, str("practise").ifEmpty { lesson }, focus)
    }

    /** A conversation's name in the history: its first question (or quote), on one line. */
    fun title(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.fromUser } ?: return "Conversation"
        val raw = first.text.ifBlank { first.quote?.text?.let { "“$it”" } ?: "" }
        val line = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            line.isEmpty() -> "Conversation"
            line.length > 80 -> line.take(79).trimEnd() + "…"
            else -> line
        }
    }

    fun encodeThreads(threads: List<ChatThread>): String = buildJsonArray {
        threads.forEach { t ->
            add(buildJsonObject {
                put("id", t.id)
                put("updated", t.updatedAt)
                put("messages", json.parseToJsonElement(encode(t.messages)))
            })
        }
    }.toString()

    fun decodeThreads(s: String?): List<ChatThread> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(s).jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                val updated = (o["updated"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: id
                val msgs = decode(o["messages"]?.toString())
                if (msgs.isEmpty()) null else ChatThread(id, updated, msgs)
            }.sortedByDescending { it.updatedAt }.take(MAX_HISTORY)
        }.getOrDefault(emptyList())
    }

    fun decode(s: String?): List<ChatMessage> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(s).jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                ChatMessage(
                    id = str("id")?.toLongOrNull() ?: return@mapNotNull null,
                    fromUser = str("user") == "true",
                    text = str("text").orEmpty(),
                    quote = str("quote")?.let { Quote(it, str("source")) },
                    made = (o["made"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { x ->
                        val mo = x as? JsonObject ?: return@mapNotNull null
                        fun ms(k: String) = (mo[k] as? JsonPrimitive)?.contentOrNull
                        Made(
                            kind = runCatching { Made.Kind.valueOf(ms("kind").orEmpty()) }.getOrNull() ?: return@mapNotNull null,
                            title = ms("title").orEmpty(), ref = ms("ref") ?: return@mapNotNull null,
                            lang = ms("lang")?.let { c -> Lang.entries.firstOrNull { it.code == c } },
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

}
