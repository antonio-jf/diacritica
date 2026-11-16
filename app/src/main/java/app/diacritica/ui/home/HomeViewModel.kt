package app.diacritica.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.data.user.HistoryItem
import app.diacritica.data.user.RehearseRound
import app.diacritica.data.user.Story
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the Home screen shows for each part of the app. */
data class HomeStats(
    val recent: List<HistoryItem> = emptyList(),
    val savedCount: Int = 0,
    val due: Int = 0,
    val newCards: Int = 0,
    /** The story to continue: most recently opened, else the newest. */
    val story: Story? = null,
    val storyTitles: Map<Lang, String> = emptyMap(),
    val storyCount: Int = 0,
    val lastRound: RehearseRound? = null,
    val roundsThisWeek: Int = 0,
    val accuracyThisWeek: Int? = null,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val db = c.userDb
    val updater get() = c.updater
    /** Re-checked every minute so cards that fall due while Home is open are counted. */
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reviewPart = combine(clock.flatMapLatest { db.cards().dueCount(it) }, db.cards().newCount()) { due, new -> due to new }

    private val libraryPart = combine(db.history().recent(), db.saved().keys()) { history, saved ->
        // One entry per word, newest first.
        history.distinctBy { it.lang + ":" + it.query.lowercase() }.take(6) to saved.size
    }

    val stats: StateFlow<HomeStats> = combine(
        reviewPart, libraryPart, c.stories.all(), db.rehearseRounds().all(), c.settings.settings,
    ) { (due, new), (recent, saved), stories, rounds, settings ->
        val story = stories.filter { it.lastOpenedAt != null }.maxByOrNull { it.lastOpenedAt ?: 0L }
            ?: stories.maxByOrNull { it.createdAt }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val week = rounds.filter { it.at >= weekAgo }
        val total = week.sumOf { it.total }
        HomeStats(
            recent = recent,
            savedCount = saved,
            due = due,
            newCards = minOf(new, settings.newCardsPerDay),
            story = story,
            storyTitles = story?.let { c.stories.titles(it) } ?: emptyMap(),
            storyCount = stories.size,
            lastRound = rounds.maxByOrNull { it.at },
            roundsThisWeek = week.size,
            accuracyThisWeek = if (total > 0) week.sumOf { it.correct } * 100 / total else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeStats())
}
