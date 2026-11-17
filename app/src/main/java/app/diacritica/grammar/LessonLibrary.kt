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
