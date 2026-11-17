package app.diacritica.ui.grammar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.grammar.GrammarPractice
import app.diacritica.grammar.GrammarStats
import app.diacritica.grammar.LessonEntry
import app.diacritica.grammar.Mistake
import app.diacritica.grammar.WeakPoint
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.LangFeedback
import app.diacritica.tests.AttemptStatus
import app.diacritica.tests.TestSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class GrammarUiState(
    val loading: Boolean = true,
    val weakPoints: List<WeakPoint> = emptyList(),
    val lessons: List<LessonEntry> = emptyList(),
    /** Languages that have weak points or lessons, for the filter chips. */
    val langs: List<Lang> = emptyList(),
    val filter: Lang? = null,
    /** "lang:type" of the weak point whose practice test is being written. */
    val generating: String? = null,
    val message: String? = null,
)

class GrammarViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(GrammarUiState())
    val state: StateFlow<GrammarUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    fun refresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val active = c.settings.settings.first().activeLanguages
            c.tests.load()
            val attempts = c.tests.attempts.value.filter { it.status == AttemptStatus.REVIEWED && it.review != null }
            val (weak, lessons) = withContext(Dispatchers.IO) {
                val mistakes = ArrayList<Mistake>()
                // Rehearse rounds of the last 60 days (the trend compares two 30-day windows).
                for (r in c.userDb.rehearseRounds().since(now - 2L * GrammarStats.WINDOW_DAYS * 24 * 3600 * 1000)) {
                    val fb = runCatching { json.decodeFromString(ListSerializer(LangFeedback.serializer()), r.feedbackJson) }.getOrDefault(emptyList())
                    fb.forEach { f -> f.errors.forEach { e -> mistakes += Mistake(f.lang, e.type, r.at, e.wrong, e.right, e.explanation) } }
                }
                for (a in attempts) a.review!!.answers.forEach { ar ->
                    ar.errors.forEach { e -> mistakes += Mistake(ar.lang, e.type, a.submittedAt ?: a.startedAt, e.wrong, e.right, e.explanation) }
                }
                val lessons = attempts.flatMap { a ->
                    a.review!!.lessons.map { LessonEntry(it, a.id, a.title, a.submittedAt ?: a.startedAt, 1) }
                } + runCatching { c.bookshelf.lessons() }.getOrDefault(emptyList()).map { (lesson, note, title) ->
                    // Lessons made from a book passage: a negative id tells the practice button there's no test behind it.
                    LessonEntry(lesson, -note.id, "“$title”", note.createdAt, 1)
                }
                GrammarStats.weakPoints(mistakes, now) to GrammarStats.lessons(lessons)
            }
            val langs = (weak.map { it.lang } + lessons.mapNotNull { it.lesson.lang }).distinct()
                .sortedBy { l -> active.indexOf(l).let { if (it < 0) 99 else it } }
            _state.update { it.copy(loading = false, weakPoints = weak, lessons = lessons, langs = langs, filter = it.filter?.takeIf { f -> f in langs }) }
        }
    }

    fun setFilter(l: Lang?) = _state.update { it.copy(filter = l) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Gemini writes a short test aimed at [w]; [onReady] starts it in Tests. */
    fun practise(w: WeakPoint, onReady: (TestSet, Lang) -> Unit) {
        val key = "${w.lang.code}:${w.type}"
        _state.update { it.copy(generating = key, message = null) }
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val result = when {
                s.geminiKey.isBlank() -> Result.failure(GeminiClient.GeminiException("Practice tests are written by Gemini: add your API key in Settings."))
                !c.online.isOnline() -> Result.failure(GeminiClient.GeminiException("You're offline. Try Rehearse this instead, or connect to write a practice test."))
                else -> runCatching { GrammarPractice.generate(c.gemini, s.geminiKey, s.geminiModel, w, s.storyLevel, s.storyHsk) }
            }
            _state.update { it.copy(generating = null) }
            result.onSuccess { onReady(it, w.lang) }
                .onFailure { e -> _state.update { it.copy(message = (e as? GeminiClient.GeminiException)?.message ?: "Couldn't write the practice test. Try again.") } }
        }
    }

    fun speak(lang: Lang, text: String) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate) }
    }
}
