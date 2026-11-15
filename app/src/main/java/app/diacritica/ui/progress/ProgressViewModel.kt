package app.diacritica.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.progress.ErrorCount
import app.diacritica.progress.LangAccuracy
import app.diacritica.progress.ProgressStats
import app.diacritica.progress.ReviewSummary
import app.diacritica.rehearse.LangFeedback
import app.diacritica.tests.AttemptStatus
import app.diacritica.tests.TestScoring
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

enum class ProgressRange(val label: String, val days: Long?) { WEEK("7 days", 7), MONTH("30 days", 30), ALL("All time", null) }

data class ProgressUiState(
    val loading: Boolean = true,
    val range: ProgressRange = ProgressRange.MONTH,
    val rounds: Int = 0,
    /** Reviewed tests in the period. */
    val tests: Int = 0,
    val accuracy: List<LangAccuracy> = emptyList(),
    val errors: List<ErrorCount> = emptyList(),
    val review: ReviewSummary = ReviewSummary(0, 0, emptyMap()),
    val matureCards: Int = 0,
    val lookups: Int = 0,
    val savedWords: Int = 0,
    val storiesOpened: Int = 0,
    val sentencesRead: Int = 0,
    /** Words read in each language of your Reader pair (Mandarin: characters). */
    val wordsRead: Map<Lang, Int> = emptyMap(),
)

class ProgressViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ProgressUiState())
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    fun refresh() = load(_state.value.range)

    fun setRange(r: ProgressRange) = load(r)

    private fun load(range: ProgressRange) {
        _state.update { it.copy(loading = true, range = range) }
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val now = System.currentTimeMillis()
            val since = range.days?.let { now - it * 24 * 3600 * 1000 } ?: 0L
            val db = c.userDb
            val next = withContext(Dispatchers.IO) {
                val stories = c.stories.all().first()

                val rounds = db.rehearseRounds().since(since)
                val feedback = rounds.flatMap { r ->
                    runCatching { json.decodeFromString(ListSerializer(LangFeedback.serializer()), r.feedbackJson) }.getOrDefault(emptyList())
                }
                // Reviewed tests count towards accuracy and mistake types like Rehearse rounds.
                c.tests.load()
                val testFeedback = c.tests.attempts.value
                    .filter { it.status == AttemptStatus.REVIEWED && (it.submittedAt ?: 0L) >= since }
                    .flatMap { a -> a.review?.let { TestScoring.asFeedback(it) }.orEmpty() }
                val testCount = c.tests.attempts.value.count { it.status == AttemptStatus.REVIEWED && (it.submittedAt ?: 0L) >= since }
                val allFeedback = feedback + testFeedback
                val review = ProgressStats.reviews(db.cards().reviewEvents(since))

                // Reading: progress in stories opened in the range, counted in your current Reader pair.
                val pair = listOf(s.readerLangA, s.readerLangB).distinct()
                val opened = stories.filter { (it.lastOpenedAt ?: 0L) >= since && it.lastOpenedAt != null }
                val words = HashMap<Lang, Int>()
                var sentences = 0
                for (st in opened) {
                    val loaded = c.stories.load(st.id) ?: continue
                    val read = loaded.sentences.take(st.lastSentence + 1)
                    sentences += read.size
                    for (l in pair) words[l] = (words[l] ?: 0) + read.sumOf { m -> m[l]?.let { ProgressStats.wordCount(it, l) } ?: 0 }
                }

                ProgressUiState(
                    loading = false, range = range,
                    rounds = rounds.size,
                    tests = testCount,
                    accuracy = ProgressStats.accuracy(allFeedback, s.activeLanguages),
                    errors = ProgressStats.errorTypes(allFeedback),
                    review = review,
                    matureCards = db.cards().matureCount(),
                    lookups = db.history().since(since).size,
                    savedWords = db.saved().allNow().size,
                    storiesOpened = opened.size,
                    sentencesRead = sentences,
                    wordsRead = words.filterValues { it > 0 },
                )
            }
            _state.value = next
        }
    }
}
