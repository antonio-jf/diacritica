package app.diacritica.grammar

import android.content.Context
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import app.diacritica.rehearse.LangFeedback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** What Home shows: today's topic and the ones saved for later. */
data class DailyState(
    val today: DailyTopic? = null,
    val later: List<DailyTopic> = emptyList(),
    val notify: Boolean = false,
    /** Notification time, minutes after midnight. */
    val notifyAt: Int = 9 * 60,
    /** Topic id → full lesson key, for lessons already written. */
    val written: Map<String, String> = emptyMap(),
)

/**
 * "Lesson of the day": one advanced grammar topic a day for French, German, Italian or Mandarin (taking turns
 * between the ones you have on), picked offline from [DailyTopics]. It skips topics you've opened or already
 * have a full lesson on, and prefers ones related to your recent Rehearse mistakes. The full lesson is written
 * by Gemini the first time you open it, then kept with your other full lessons.
 */
class DailyLessons(private val c: AppContainer, context: Context) {
    private val prefs = context.getSharedPreferences("daily_lesson", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(DailyState())
    val state: StateFlow<DailyState> = _state.asStateFlow()

    private fun set(key: String) = prefs.getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }
    private fun put(key: String, v: List<String>) = prefs.edit().putString(key, v.distinct().joinToString(",")).apply()
    private fun written(): Map<String, String> = set("written").mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    private fun publish(today: DailyTopic?) {
        _state.value = DailyState(
            today = today,
            later = set("later").mapNotNull { id -> DailyTopics.all.firstOrNull { it.id == id } },
            notify = prefs.getBoolean("notify", false),
            notifyAt = prefs.getInt("notify_at", 9 * 60),
            written = written(),
        )
    }

    /** Today's topic: the one already chosen today, or a new one. Null when none of the four languages is on. */
    suspend fun today(): DailyTopic? {
        val day = LocalDate.now().toEpochDay()
        val kept = prefs.getString("topic", null)?.takeIf { prefs.getLong("day", -1) == day }
            ?.let { id -> DailyTopics.all.firstOrNull { it.id == id } }
        val langs = langs()
        val topic = kept?.takeIf { it.lang in langs } ?: pick(day, langs, exclude = emptySet())?.also {
            prefs.edit().putLong("day", day).putString("topic", it.id).apply()
        }
        publish(topic)
        return topic
    }

    /** A different topic for today (the skipped one won't come back soon). */
    suspend fun another() {
        val current = _state.value.today ?: return
        put("seen", set("seen") + current.id)
        val day = LocalDate.now().toEpochDay()
        val next = pick(day, langs(), exclude = setOf(current.id), offset = 1 + prefs.getInt("skips", 0))
        prefs.edit().putLong("day", day).putString("topic", next?.id).putInt("skips", prefs.getInt("skips", 0) + 1).apply()
        publish(next)
    }

    /** Keeps today's topic for later and shows another. */
    suspend fun later() {
        val current = _state.value.today ?: return
        put("later", set("later") + current.id)
        another()
    }

    fun removeLater(t: DailyTopic) { put("later", set("later") - t.id); publish(_state.value.today) }

    /**
     * The full lesson's key for [t], writing it with Gemini first if needed. Opening a topic marks it seen and
     * takes it off the later list.
     */
    suspend fun open(t: DailyTopic): Result<String> {
        put("seen", set("seen") + t.id)
        put("later", set("later") - t.id)
        written()[t.id]?.let { key ->
            c.lessons.load()
            if (key in c.lessons.full.value || runCatching { c.bookshelf.fullLessons().any { it.key == key } }.getOrDefault(false)) {
                publish(_state.value.today); return Result.success(key)
            }
        }
        val r = c.lessons.askNow(t.request, t.lang)
        r.onSuccess { key -> put("written", set("written").filter { !it.startsWith(t.id + "=") } + "${t.id}=$key") }
        publish(_state.value.today)
        return r
    }

    fun setNotify(on: Boolean, at: Int = prefs.getInt("notify_at", 9 * 60)) {
        prefs.edit().putBoolean("notify", on).putInt("notify_at", at).apply()
        publish(_state.value.today)
    }

    val notifyOn: Boolean get() = prefs.getBoolean("notify", false)
    val notifyAt: Int get() = prefs.getInt("notify_at", 9 * 60)

    private suspend fun langs(): List<Lang> {
        val active = c.settings.settings.first().activeLanguages
        return listOf(Lang.FR, Lang.DE, Lang.IT, Lang.ZH).filter { it in active }
    }

    private suspend fun pick(day: Long, langs: List<Lang>, exclude: Set<String>, offset: Int = 0): DailyTopic? {
        if (langs.isEmpty()) return null
        val seen = set("seen").toSet()
        val later = set("later").toSet()
        c.lessons.load()
        val haveTitles = runCatching { c.bookshelf.fullLessons() }.getOrDefault(emptyList()).mapNotNull { f ->
            runCatching { json.decodeFromString(app.diacritica.tests.Lesson.serializer(), f.json) }.getOrNull()
        }.map { key(it.title) }.toSet() + c.lessons.full.value.values.map { key(it.title) }
        val weak = weakTypes()
        // Languages take turns by day; if one has nothing left, the next one.
        for (k in langs.indices) {
            val lang = langs[((day + k) % langs.size).toInt()]
            fun usable(t: DailyTopic, strict: Boolean) = t.lang == lang && t.id !in exclude && t.id !in later &&
                (!strict || (t.id !in seen && key(t.title) !in haveTitles))
            var pool = DailyTopics.all.filter { usable(it, strict = true) }
            if (pool.isEmpty()) {
                // Everything in this language has been seen: start over with it (still not today's skipped one).
                put("seen", set("seen").filter { !it.startsWith(lang.code + "-") })
                pool = DailyTopics.all.filter { usable(it, strict = false) }
            }
            if (pool.isEmpty()) continue
            val w = weak[lang].orEmpty()
            val ranked = pool.sortedWith(
                compareByDescending<DailyTopic> { t -> t.tags.sumOf { w[tag(it)] ?: 0 } }
                    .thenBy { t -> (t.id.hashCode().toLong() * 31 + day).hashCode() },
            )
            return ranked[offset % ranked.size]
        }
        return null
    }

    private fun key(title: String) = TextNorm.normalize(title).replace(Regex("[^a-z0-9\\p{L}]"), "")

    /** Rehearse error types grouped the way topics are tagged. */
    private fun tag(t: String): String = when (val n = t.lowercase().replace(' ', '-')) {
        "tense", "mood" -> "conjugation"
        "prepositions", "measure-word" -> "word-choice"
        else -> n
    }

    /** Your mistake types per language in the last 30 days of Rehearse. */
    private suspend fun weakTypes(): Map<Lang, Map<String, Int>> = runCatching {
        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val out = HashMap<Lang, HashMap<String, Int>>()
        for (r in c.userDb.rehearseRounds().since(since)) {
            val fb = runCatching { json.decodeFromString(ListSerializer(LangFeedback.serializer()), r.feedbackJson) }.getOrDefault(emptyList())
            for (f in fb) for (e in f.errors) {
                val m = out.getOrPut(f.lang) { HashMap() }
                m[tag(e.type)] = (m[tag(e.type)] ?: 0) + 1
            }
        }
        out as Map<Lang, Map<String, Int>>
    }.getOrDefault(emptyMap())
}
