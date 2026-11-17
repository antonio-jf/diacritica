package app.diacritica.ui.tests

import app.diacritica.tests.TestRequest
import app.diacritica.tests.TestGenerator
import app.diacritica.undo.deleteTestAttempt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import app.diacritica.tests.AttemptStatus
import app.diacritica.tests.Lesson
import app.diacritica.tests.TestAttempt
import app.diacritica.tests.TestBank
import app.diacritica.tests.TestReviewer
import app.diacritica.tests.TestSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestsPage {
    data object List : TestsPage
    data class Take(val attemptId: Long) : TestsPage
    data class Result(val attemptId: Long) : TestsPage
}

data class TestsUiState(
    val page: TestsPage = TestsPage.List,
    /** Sentence shown while taking a test. */
    val position: Int = 0,
    val reviewing: Boolean = false,
    val message: String? = null,
    /** Gemini is writing a new test. */
    val generating: Boolean = false,
    val generateError: String? = null,
)

class TestsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TestsUiState())
    val state: StateFlow<TestsUiState> = _state.asStateFlow()
    val attempts: StateFlow<List<TestAttempt>> = c.tests.attempts
    val tests: List<TestSet> get() = c.tests.tests
    /** Tests written on demand, newest first. */
    val generated: StateFlow<List<TestSet>> = c.tests.generated

    /** Asks Gemini for a new test with these settings; it appears under "Your tests". */
    fun generate(r: TestRequest) {
        if (_state.value.generating) return
        _state.update { it.copy(generating = true, generateError = null) }
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val result = when {
                s.geminiKey.isBlank() -> Result.failure(GeminiClient.GeminiException("New tests are written by Gemini: add your API key in Settings."))
                !c.online.isOnline() -> Result.failure(GeminiClient.GeminiException("You're offline. Connect to write a new test."))
                else -> runCatching { TestGenerator.generate(c.gemini, s.geminiKey, s.geminiModel, r) }
            }
            result.onSuccess { t -> c.tests.addGenerated(t) }
            _state.update {
                it.copy(generating = false, generateError = result.exceptionOrNull()?.let { e -> (e as? GeminiClient.GeminiException)?.message ?: "Couldn't write the test. Try again." })
            }
        }
    }

    fun deleteGenerated(t: TestSet) {
        viewModelScope.launch {
            c.tests.deleteGenerated(t.id)
            c.undo.offer("“${t.title}” deleted") { c.tests.addGenerated(t) }
        }
    }
    private var saveJob: Job? = null

    init { viewModelScope.launch { c.tests.load() } }

    fun testFor(a: TestAttempt): TestSet? = a.custom ?: c.tests.test(a.testId)

    /** Your languages minus the one the sentences are shown in; by default the ones you're learning (not EN/ES). */
    fun defaultTargets(active: List<Lang>, source: Lang): List<Lang> =
        active.filter { it != source && it != Lang.EN && it != Lang.ES }.ifEmpty { active.filter { it != source } }

    /** Starts a test, or resumes its unfinished draft with the same languages. */
    fun start(test: TestSet, source: Lang, langs: List<Lang>) {
        if (langs.isEmpty()) return
        viewModelScope.launch {
            c.tests.load()
            val draft = attempts.value.firstOrNull { it.testId == test.id && it.status == AttemptStatus.DRAFT && it.source == source && it.langs == langs }
            val a = draft ?: TestAttempt(
                id = System.currentTimeMillis(), testId = test.id, title = test.title, source = source, langs = langs,
                startedAt = System.currentTimeMillis(), custom = test.takeIf { c.tests.tests.none { b -> b.id == it.id } },
            ).also { c.tests.save(it) }
            val firstEmpty = (0 until test.items.size).firstOrNull { i -> langs.any { a.answer(i, it).isBlank() } } ?: 0
            _state.update { TestsUiState(page = TestsPage.Take(a.id), position = firstEmpty) }
        }
    }

    /** A follow-up test from a lesson's practice sentences, in the same languages as [from]. */
    fun practise(lesson: Lesson, from: TestAttempt) {
        val base = testFor(from) ?: return
        if (lesson.practice.isEmpty()) return
        val langs = lesson.lang?.takeIf { it in from.langs }?.let { listOf(it) } ?: from.langs
        start(TestBank.fromLesson(lesson, base), Lang.EN, langs)
    }

    /** [practise] for a lesson shown on the Grammar page, which knows only the attempt's id. */
    fun practiseFrom(lesson: Lesson, attemptId: Long) {
        if (attemptId < 0) return practiseBookLesson(lesson)
        c.tests.get(attemptId)?.let { practise(lesson, it) }
    }

    /** A lesson made from a book passage (Books › Break down): its practice sentences, into the book's language. */
    private fun practiseBookLesson(lesson: Lesson) {
        if (lesson.practice.isEmpty()) return
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val base = app.diacritica.tests.TestSet(
                id = "book", title = lesson.title,
                level = app.diacritica.rehearse.Difficulty.band(s.rehearseLevel).cefrLabel.take(2),
                hsk = app.diacritica.rehearse.Difficulty.band(s.rehearseZhLevel).hsk,
                focus = "mixed", description = lesson.why, items = emptyList(),
            )
            start(TestBank.fromLesson(lesson, base), Lang.EN, listOfNotNull(lesson.lang ?: s.activeLanguages.firstOrNull { it != Lang.EN }))
        }
    }

    fun open(a: TestAttempt) {
        _state.update {
            when (a.status) {
                AttemptStatus.DRAFT -> TestsUiState(page = TestsPage.Take(a.id))
                else -> TestsUiState(page = TestsPage.Result(a.id))
            }
        }
    }

    fun backToList() {
        saveJob?.cancel()
        viewModelScope.launch { c.tests.persist() }
        _state.update { TestsUiState() }
    }

    fun goTo(i: Int) = _state.update { it.copy(position = i.coerceAtLeast(0)) }

    fun setAnswer(id: Long, item: Int, lang: Lang, text: String) {
        // Memory first, so the field never lags; the file is written once typing pauses.
        c.tests.edit(id) { it.withAnswer(item, lang, text) } ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            c.tests.persist()
        }
    }

    override fun onCleared() {
        // Not in viewModelScope: it is being cancelled.
        kotlinx.coroutines.runBlocking { c.tests.persist() }
    }

    fun delete(id: Long) {
        viewModelScope.launch { c.deleteTestAttempt(id) }
        _state.update { TestsUiState() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Sends the attempt to Gemini. Without a key or internet it stays saved as "waiting for review". */
    fun submit(id: Long) {
        saveJob?.cancel()
        val a0 = c.tests.get(id) ?: return
        val test = testFor(a0) ?: return
        _state.update { it.copy(page = TestsPage.Result(id), reviewing = true, message = null) }
        viewModelScope.launch {
            val pending = a0.copy(status = AttemptStatus.PENDING, submittedAt = a0.submittedAt ?: System.currentTimeMillis(), error = null)
            c.tests.save(pending)
            val s = c.settings.settings.first()
            if (s.geminiKey.isBlank()) {
                c.tests.save(pending.copy(error = "Reviews are written by Gemini. Add your Gemini API key in Settings, then send it again; your answers are saved."))
                _state.update { it.copy(reviewing = false) }
                return@launch
            }
            if (!c.online.isOnline()) {
                c.tests.save(pending.copy(error = "You're offline. Your answers are saved; send them for review when you're connected."))
                _state.update { it.copy(reviewing = false) }
                return@launch
            }
            val result = runCatching { TestReviewer.review(c.gemini, s.geminiKey, s.geminiModel, test, pending) }
            result.onSuccess { review ->
                c.tests.save(pending.copy(status = AttemptStatus.REVIEWED, review = review, error = null))
            }.onFailure { e ->
                val msg = (e as? GeminiClient.GeminiException)?.message ?: "The review failed (${e.message ?: e.javaClass.simpleName}). Try again."
                c.tests.save(pending.copy(error = msg))
            }
            _state.update { it.copy(reviewing = false) }
        }
    }

    /** Takes the same test again with the same languages (a fresh attempt). */
    fun retake(a: TestAttempt) {
        val test = testFor(a) ?: return
        viewModelScope.launch {
            val fresh = TestAttempt(
                id = System.currentTimeMillis(), testId = test.id, title = test.title, source = a.source, langs = a.langs,
                startedAt = System.currentTimeMillis(), custom = a.custom,
            )
            c.tests.save(fresh)
            _state.update { TestsUiState(page = TestsPage.Take(fresh.id)) }
        }
    }

    fun speak(lang: Lang, text: String) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate) }
    }
}
