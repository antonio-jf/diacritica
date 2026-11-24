package app.diacritica.grammar

import app.diacritica.mybooks.Bookshelf
import app.diacritica.rehearse.GeminiClient
import app.diacritica.settings.SettingsRepository
import app.diacritica.tests.Lesson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Full lessons, shared by every screen that shows a lesson. Writing one keeps going if you leave the screen,
 * and it's stored so each lesson costs one Gemini request.
 */
class LessonLibrary(
    private val bookshelf: Bookshelf,
    private val gemini: GeminiClient,
    private val settings: SettingsRepository,
    private val isOnline: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _full = MutableStateFlow<Map<String, Lesson>>(emptyMap())
    /** Full lessons by [LessonWriter.key]. */
    val full: StateFlow<Map<String, Lesson>> = _full.asStateFlow()

    private val _writing = MutableStateFlow<Set<String>>(emptySet())
    val writing: StateFlow<Set<String>> = _writing.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** A lesson you asked for: being written, the error if it failed, and the key of the last one written. */
    data class Asking(val busy: Boolean = false, val error: String? = null, val done: String? = null)
    private val _asking = MutableStateFlow(Asking())
    val asking: StateFlow<Asking> = _asking.asStateFlow()

    /** Writes a full lesson on whatever you described (a word, a character, a structure) in [lang]. */
    fun ask(request: String, lang: app.diacritica.data.Lang) {
        if (request.isBlank() || _asking.value.busy) return
        _asking.value = Asking(busy = true)
        scope.launch {
            val s = settings.settings.first()
            val result = when {
                s.geminiKey.isBlank() -> Result.failure(GeminiClient.GeminiException("Lessons are written by Gemini: add your API key in Settings."))
                !isOnline() -> Result.failure(GeminiClient.GeminiException("You're offline. Connect to ask for a lesson."))
                else -> runCatching { LessonWriter.ask(gemini, s.geminiKey, s.geminiModel, request, lang, s.storyLevel, s.storyHsk) }
            }
            result.onSuccess { l ->
                val key = LessonWriter.key(l)
                runCatching { bookshelf.saveFullLesson(key, json.encodeToString(Lesson.serializer(), l)) }
                _full.update { it + (key to l) }
                _asking.value = Asking(done = key)
            }.onFailure { e ->
                _asking.value = Asking(error = (e as? GeminiClient.GeminiException)?.message ?: "Couldn't write the lesson. Try again.")
            }
        }
    }

    fun consumeAsked() = _asking.update { it.copy(done = null) }

    /**
     * Writes and keeps a full lesson on [request] in [lang], returning its key (for Lesson of the day, which
     * shows its own progress instead of Grammar's "Ask for a lesson" card).
     */
    suspend fun askNow(request: String, lang: app.diacritica.data.Lang): Result<String> {
        val s = settings.settings.first()
        val result = when {
            s.geminiKey.isBlank() -> Result.failure(GeminiClient.GeminiException("Lessons are written by Gemini: add your API key in Settings › Rehearse."))
            !isOnline() -> Result.failure(GeminiClient.GeminiException("You're offline. Connect once to write this lesson; after that it's kept."))
            else -> runCatching { LessonWriter.ask(gemini, s.geminiKey, s.geminiModel, request, lang, s.storyLevel, s.storyHsk) }
        }
        return result.map { l ->
            val key = LessonWriter.key(l)
            runCatching { bookshelf.saveFullLesson(key, json.encodeToString(Lesson.serializer(), l)) }
            _full.update { it + (key to l) }
            key
        }
    }

    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            val all = runCatching { bookshelf.fullLessons() }.getOrDefault(emptyList()).mapNotNull { f ->
                runCatching { json.decodeFromString(Lesson.serializer(), f.json) }.getOrNull()?.let { f.key to it }
            }.toMap()
            _full.update { all + it }
        }
    }

    /** The lesson to show: the full one if it has been written. */
    fun shown(l: Lesson): Lesson = _full.value[LessonWriter.key(l)] ?: l

    /** Writes the full lesson in the background. With [quiet], problems (no key, offline) are not reported. */
    fun write(l: Lesson, quiet: Boolean = false) {
        val key = LessonWriter.key(l)
        if (l.full || key in _full.value || key in _writing.value) return
        _writing.update { it + key }
        _errors.update { it - key }
        scope.launch {
            val s = settings.settings.first()
            val result = when {
                s.geminiKey.isBlank() -> Result.failure(GeminiClient.GeminiException("Full lessons are written by Gemini: add your API key in Settings."))
                !isOnline() -> Result.failure(GeminiClient.GeminiException("You're offline. Connect to write the full lesson."))
                else -> runCatching { LessonWriter.write(gemini, s.geminiKey, s.geminiModel, l, s.storyLevel, s.storyHsk) }
            }
            result.onSuccess { full ->
                runCatching { bookshelf.saveFullLesson(key, json.encodeToString(Lesson.serializer(), full)) }
                _full.update { it + (key to full) }
            }.onFailure { e ->
                if (!quiet) _errors.update { it + (key to ((e as? GeminiClient.GeminiException)?.message ?: "Couldn't write the full lesson. Try again.")) }
            }
            _writing.update { it - key }
        }
    }
}
