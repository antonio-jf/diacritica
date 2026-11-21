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
    /** Translate / Use cards: what you typed. */
    val answer: String = "",
    /** Translate cards: the sentence to translate (null while it's being found). */
    val task: SentenceTask? = null,
    val taskLoading: Boolean = false,
    val checking: Boolean = false,
    val feedback: SentenceFeedback? = null,
    val checkError: String? = null,
    /** The rating the check suggests (highlighted, still your choice). */
    val suggested: Fsrs.Rating? = null,
)

/** A sentence to translate: [prompt] in [promptLang], with reference translations when there are any. */
data class SentenceTask(val prompt: String, val promptLang: app.diacritica.data.Lang, val references: List<String>, val source: String)

/** How a typed sentence was judged. [level]: 2 right, 1 small mistakes, 0 wrong. */
data class SentenceFeedback(val level: Int, val title: String, val corrected: String?, val notes: List<String>, val natural: String?)

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val dao = c.userDb.cards()
    /** The shared word sheet ("Word details" on a revealed card). */
    val lookup = app.diacritica.ui.components.WordLookup(c, viewModelScope, source = "Review")
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
            c.review.syncSentenceKinds(
                buildSet {
                    if (s.reviewTranslateCards) add(app.diacritica.data.user.CardKind.TRANSLATE)
                    if (s.reviewUseCards) add(app.diacritica.data.user.CardKind.USE)
                },
            )
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
                answer = "", task = null, taskLoading = item?.kind == app.diacritica.data.user.CardKind.TRANSLATE, checking = false,
                feedback = null, checkError = null, suggested = null,
                previews = next?.let { card -> Fsrs.Rating.entries.associateWith { r -> Fsrs.preview(card, r, now) } } ?: emptyMap(),
            )
        }
        if (item?.kind == app.diacritica.data.user.CardKind.TRANSLATE) prepareTranslate(item)
    }

    fun setAnswer(v: String) = _state.update { it.copy(answer = v) }

    /**
     * Finds a sentence with the card's word in a language you know: a Tatoeba sentence first, then the
     * dictionary's example, then (with a key) one written by Gemini.
     */
    private fun prepareTranslate(item: ReviewItem) {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val lang = item.lang
            val known = (listOf(app.diacritica.data.Lang.EN, app.diacritica.data.Lang.ES) + s.activeLanguages).distinct()
                .filter { it != lang && it in s.activeLanguages }
            var task: SentenceTask? = null
            val hit = runCatching { c.sentences.nextWithWord(listOf(lang to item.card.word), app.diacritica.rehearse.Difficulty.band(s.rehearseLevel)) }.getOrNull()
            if (hit != null) {
                val g = hit.first
                val from = known.firstOrNull { !g.texts[it].isNullOrEmpty() }
                val refs = g.texts[lang].orEmpty()
                if (from != null && refs.isNotEmpty()) task = SentenceTask(g.texts[from]!!.first(), from, refs, "Tatoeba")
            }
            if (task == null) {
                val w = item.word
                if (w?.example != null && w.exampleEn != null && app.diacritica.data.Lang.EN in known) {
                    task = SentenceTask(w.exampleEn, app.diacritica.data.Lang.EN, listOf(w.example), "Wiktionary example")
                }
            }
            if (task == null && s.geminiKey.isNotBlank()) {
                val from = known.firstOrNull() ?: app.diacritica.data.Lang.EN
                val hsk = if (lang == app.diacritica.data.Lang.ZH) app.diacritica.rehearse.Difficulty.band(s.rehearseZhLevel).hsk else null
                val out = runCatching { c.gemini.sentenceWith(s.geminiKey, s.geminiModel, lang, item.card.word, item.meaning, from, hsk) }.getOrNull()
                if (out != null && out.second.isNotBlank()) task = SentenceTask(out.second, from, listOf(out.first), "written by Gemini")
            }
            _state.update { st -> if (st.item?.card?.id == item.card.id) st.copy(task = task, taskLoading = false) else st }
        }
    }

    /** Checks what you typed: exact matches offline, everything else with Gemini; without a key, shows the answer. */
    fun checkSentence() {
        val st = _state.value
        val item = st.item ?: return
        val answer = st.answer.trim()
        if (answer.isEmpty()) { reveal(); return }
        viewModelScope.launch {
            val s = c.settings.settings.first()
            _state.update { it.copy(checking = true, checkError = null) }
            val fb: SentenceFeedback? = runCatching {
                when (item.kind) {
                    app.diacritica.data.user.CardKind.TRANSLATE -> {
                        val task = st.task ?: return@runCatching null
                        val norm = { x: String -> app.diacritica.data.TextNorm.normalize(x).replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex(" +"), " ").trim() }
                        if (task.references.any { norm(it) == norm(answer) }) {
                            SentenceFeedback(2, "Right", null, emptyList(), null)
                        } else if (s.geminiKey.isBlank()) {
                            null
                        } else {
                            val f = c.gemini.grade(
                                s.geminiKey, s.geminiModel, task.promptLang, task.prompt,
                                listOf(app.diacritica.rehearse.GeminiClient.AnswerInput(item.lang, answer, task.references)),
                                strictAccents = s.strictAccents, strictTones = s.strictTones,
                            )[item.lang]
                            f?.let {
                                val level = when (it.verdict) {
                                    app.diacritica.rehearse.Verdict.CORRECT -> 2
                                    app.diacritica.rehearse.Verdict.ACCEPTABLE -> 1
                                    else -> 0
                                }
                                SentenceFeedback(
                                    level, listOf("Not quite", "Almost", "Right")[level], it.corrected,
                                    it.errors.map { e -> "${e.wrong} → ${e.right}: ${e.explanation}" }, it.alternatives.firstOrNull(),
                                )
                            }
                        }
                    }
                    app.diacritica.data.user.CardKind.USE -> {
                        if (s.geminiKey.isBlank()) null else {
                            val word = item.word?.word ?: item.card.word
                            val u = c.gemini.checkUsage(s.geminiKey, s.geminiModel, item.lang, word, item.meaning, answer)
                            val level = when {
                                !u.usesWord -> 0
                                u.verdict == "correct" -> 2
                                u.verdict == "minor" -> 1
                                else -> 0
                            }
                            SentenceFeedback(
                                level, if (!u.usesWord) "The word isn't in your sentence" else listOf("Not quite", "Almost", "Right")[level],
                                u.corrected, listOf(u.explanation).filter { it.isNotBlank() }, u.natural,
                            )
                        }
                    }
                    else -> null
                }
            }.onFailure { e -> _state.update { it.copy(checkError = e.message ?: "Gemini couldn't check it.") } }.getOrNull()
            val suggested = when (fb?.level) { 2 -> Fsrs.Rating.GOOD; 1 -> Fsrs.Rating.HARD; 0 -> Fsrs.Rating.AGAIN; else -> null }
            _state.update { it.copy(checking = false, feedback = fb, suggested = suggested, revealed = true) }
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
