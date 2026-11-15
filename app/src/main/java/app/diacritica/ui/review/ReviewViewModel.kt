package app.diacritica.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.user.Card
import app.diacritica.review.Fsrs
import app.diacritica.review.ReviewItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val item: ReviewItem? = null,
    val revealed: Boolean = false,
    /** For article cards: what the user tapped. */
    val pickedArticle: String? = null,
    val remaining: Int = 0,
    val doneThisSession: Int = 0,
    /** Interval preview per rating, e.g. AGAIN → "1 min". */
    val previews: Map<Fsrs.Rating, String> = emptyMap(),
)

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val dao = c.userDb.cards()
    val dueCount = dao.dueCount(System.currentTimeMillis()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val newCount = dao.newCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val total = dao.total().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val nextDue = dao.nextDue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var queue: ArrayDeque<Card> = ArrayDeque()

    /** Called whenever the Review tab is opened. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val s = c.settings.settings.first()
            c.review.syncSavedWords(s.activeLanguages, s.script)
            if (s.reviewFromHistory) {
                val newest = c.review.syncHistory(c.settings.historySyncedAt(), s.activeLanguages, s.script)
                c.settings.setHistorySyncedAt(newest)
            }
            queue = ArrayDeque(c.review.queue(s.newCardsPerDay))
            showNext()
        }
    }

    private suspend fun showNext() {
        val s = c.settings.settings.first()
        val next = queue.removeFirstOrNull()
        val item = next?.let { c.review.load(it, s.activeLanguages, s.script) }
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                loading = false, item = item, revealed = false, pickedArticle = null, remaining = queue.size + (if (item != null) 1 else 0),
                previews = next?.let { card -> Fsrs.Rating.entries.associateWith { r -> Fsrs.preview(card, r, now) } } ?: emptyMap(),
            )
        }
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun pickArticle(a: String) = _state.update { it.copy(pickedArticle = a, revealed = true) }

    fun rate(r: Fsrs.Rating) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val updated = c.review.rate(item.card, r)
            // Learning steps come back within this session.
            if (updated.due - System.currentTimeMillis() <= 15 * 60_000L) queue.addLast(updated)
            _state.update { it.copy(doneThisSession = it.doneThisSession + 1) }
            showNext()
        }
    }

    fun speak() {
        val w = _state.value.item?.word ?: return
        viewModelScope.launch {
            val text = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
            c.speaker.speak(text, w.lang, c.settings.settings.first().speechRate)
        }
    }
}
