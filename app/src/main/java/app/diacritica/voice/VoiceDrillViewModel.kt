package app.diacritica.voice

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.data.WordInfo
import app.diacritica.data.user.CardKind
import app.diacritica.rehearse.Difficulty
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.LangFeedback
import app.diacritica.review.Fsrs
import app.diacritica.vocab.CustomVocabStore
import app.diacritica.vocab.VocabSet
import app.diacritica.vocab.VocabSets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoicePhase { SETUP, PREPARING, ASKING, LISTENING, CHECKING, FEEDBACK, DONE }

sealed interface PronState {
    data object Idle : PronState
    data class Recording(val level: Float = 0f) : PronState
    data object Checking : PronState
    data class Result(val p: GeminiClient.Pronunciation) : PronState
    data class Error(val message: String) : PronState
}

data class VoiceResult(val item: VoiceItem, val verdict: VoiceVerdict, val heard: String?)

data class VoiceUi(
    val config: VoiceConfig = VoiceConfig(),
    val active: List<Lang> = emptyList(),
    val sets: List<VocabSet> = emptyList(),
    val savedCount: Int = 0,
    /** Offline speech packs per practised language (null: the phone can't tell). */
    val packs: Set<Lang>? = null,
    val phase: VoicePhase = VoicePhase.SETUP,
    val items: List<VoiceItem> = emptyList(),
    val index: Int = 0,
    val partial: String = "",
    val level: Float = 0f,
    val heard: Heard? = null,
    val verdict: VoiceVerdict? = null,
    val note: String? = null,
    /** "I said it right" was tapped. */
    val overridden: Boolean = false,
    val pron: PronState = PronState.Idle,
    /** Gemini's check of a spoken sentence translation. */
    val aiFeedback: LangFeedback? = null,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    val results: List<VoiceResult> = emptyList(),
    val error: String? = null,
) {
    val item: VoiceItem? get() = items.getOrNull(index)
}

class VoiceDrillViewModel(private val c: AppContainer, private val app: Application) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUi())
    val state: StateFlow<VoiceUi> = _state.asStateFlow()

    private val listener = VoiceListener(app)
    private var loop: Job? = null
    private var pronJob: Job? = null
    private var rated = false

    init {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val sets = withContext(Dispatchers.IO) {
                runCatching { VocabSets.all(app) }.getOrDefault(emptyList()) +
                    runCatching { CustomVocabStore(java.io.File(app.filesDir, "vocab/custom.json")).load() }.getOrDefault(emptyList())
            }
            val saved = c.userDb.saved().allNow().size
            _state.update { it.copy(config = s.voice, active = s.activeLanguages, sets = sets, savedCount = saved) }
            refreshPacks()
        }
    }

    // ---------------------------------------------------------------- setup

    fun setConfig(v: VoiceConfig) {
        _state.update { it.copy(config = v) }
        viewModelScope.launch { c.settings.setVoiceConfig(v) }
        refreshPacks()
    }

    fun refreshPacks() {
        viewModelScope.launch {
            val st = _state.value
            val langs = (st.config.targetsFor(st.active) + st.config.basesFor(st.active)).distinct()
            _state.update { it.copy(packs = runCatching { listener.offlinePacks(langs) }.getOrNull()) }
        }
    }

    fun downloadPack(lang: Lang) = listener.downloadPack(lang)

    // ---------------------------------------------------------------- session

    fun start() {
        stopAll()
        viewModelScope.launch {
            _state.update { it.copy(phase = VoicePhase.PREPARING, error = null, results = emptyList()) }
            val items = runCatching { buildItems(_state.value.config) }.getOrDefault(emptyList())
            if (items.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = VoicePhase.SETUP,
                        error = when (it.config.source) {
                            VoiceSource.SAVED -> "No saved words in the languages you picked. Save some words, or choose Vocabulary."
                            VoiceSource.SENTENCES -> "No sentences found for these languages."
                            VoiceSource.VOCAB -> "No words found in this set for these languages."
                        },
                    )
                }
                return@launch
            }
            _state.update { it.copy(items = items, index = 0) }
            runItem(fromListening = false)
        }
    }

    /** Practise the ones you missed again. */
    fun retryMissed() {
        val missed = _state.value.results.filter { it.verdict != VoiceVerdict.RIGHT }.map { it.item }
        if (missed.isEmpty()) return
        stopAll()
        retrying = true
        _state.update { it.copy(items = missed.shuffled(), index = 0, results = emptyList()) }
        runItem(fromListening = false)
    }

    fun backToSetup() {
        stopAll()
        _state.update { it.copy(phase = VoicePhase.SETUP, items = emptyList(), results = emptyList(), error = null) }
    }

    /** Ends the session and shows the summary. */
    fun finish() {
        commitCurrent()
        stopAll()
        _state.update { it.copy(phase = VoicePhase.DONE) }
    }

    fun repeatQuestion() = runItem(fromListening = false)

    fun tryAgain() = runItem(fromListening = true)

    fun markRight() {
        holdAutoNext()
        _state.update { it.copy(verdict = VoiceVerdict.RIGHT, overridden = true) }
    }

    fun next() {
        commitCurrent()
        stopAll()
        val st = _state.value
        if (st.index + 1 >= st.items.size) { _state.update { it.copy(phase = VoicePhase.DONE) }; return }
        _state.update { it.copy(index = it.index + 1) }
        topUp()
        runItem(fromListening = false)
    }

    fun playAnswer(slow: Boolean) {
        holdAutoNext()
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val rate = c.settings.settings.first().speechRate
            c.speaker.speakAndWait(item.display, item.answer, if (slow) rate * 0.6f else rate)
        }
    }

    fun playQuestion() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val rate = c.settings.settings.first().speechRate
            c.speaker.speakAndWait(item.askText, item.ask, rate)
        }
    }

    // ---------------------------------------------------------------- one question

    private var autoNextJob: Job? = null

    private fun holdAutoNext() { autoNextJob?.cancel(); autoNextJob = null }

    private fun runItem(fromListening: Boolean) {
        loop?.cancel(); holdAutoNext(); pronJob?.cancel(); listener.stop(); c.speaker.stop()
        rated = false
        val item = _state.value.item ?: return
        _state.update {
            it.copy(
                phase = if (fromListening) VoicePhase.LISTENING else VoicePhase.ASKING, partial = "", heard = null, verdict = null,
                note = null, overridden = false, pron = PronState.Idle, aiFeedback = null, aiLoading = false, aiError = null,
            )
        }
        loop = viewModelScope.launch {
            val s = c.settings.settings.first()
            val cfg = _state.value.config
            if (!fromListening) {
                for ((text, lang) in VoicePhrases.question(item)) {
                    if (!c.speaker.speakAndWait(text, lang, s.speechRate)) break
                }
            }
            _state.update { it.copy(phase = VoicePhase.LISTENING, partial = "", level = 0f) }
            val packs = _state.value.packs
            val heard = listener.listen(
                item.answer, preferOffline = packs?.contains(item.answer) == true,
                onPartial = { p -> _state.update { it.copy(partial = p) } },
                onLevel = { l -> _state.update { it.copy(level = l) } },
            )
            _state.update { it.copy(phase = VoicePhase.CHECKING, heard = heard) }
            var result = VoiceCheck.check(item, heard.texts)
            // Mandarin: when the recognizer chose other characters, compare by sound.
            if (result.verdict != VoiceVerdict.RIGHT && item.answer == Lang.ZH && heard.texts.isNotEmpty()) {
                val pins = heard.texts.associateWith { h -> runCatching { c.dictionary.pinyinOf(h) }.getOrNull() }
                result = VoiceCheck.check(item, heard.texts) { h -> pins[h] }
            }
            if (heard.texts.isEmpty() && heard.error != null && heard.error != "Didn't catch that.") {
                _state.update { it.copy(phase = VoicePhase.FEEDBACK, verdict = null, note = heard.error) }
                return@launch
            }
            _state.update { it.copy(phase = VoicePhase.FEEDBACK, verdict = result.verdict, note = result.note) }
            for ((text, lang) in VoicePhrases.feedback(result.verdict, item)) {
                if (!c.speaker.speakAndWait(text, lang, s.speechRate)) break
            }
            if (cfg.autoNext && result.verdict == VoiceVerdict.RIGHT) {
                autoNextJob = viewModelScope.launch { delay(1_200); next() }
            } else if (cfg.autoNext) {
                autoNextJob = viewModelScope.launch { delay(3_500); next() }
            }
        }
    }

    /** Records the current answer (once) and updates Review if that's turned on. */
    private fun commitCurrent() {
        val st = _state.value
        val item = st.item ?: return
        if (rated || st.phase != VoicePhase.FEEDBACK) return
        val verdict = st.verdict ?: VoiceVerdict.NOT_HEARD
        rated = true
        _state.update { it.copy(results = it.results + VoiceResult(item, verdict, st.heard?.best)) }
        if (st.config.countInReview) item.review?.let { (lang, word) ->
            viewModelScope.launch { rateInReview(lang, word, item.mode, verdict) }
        }
    }

    private suspend fun rateInReview(lang: Lang, word: String, mode: VoiceMode, v: VoiceVerdict) {
        val kind = when (mode) {
            VoiceMode.SAY -> CardKind.RECALL
            VoiceMode.LISTEN -> CardKind.LISTENING
            VoiceMode.ECHO -> return
        }
        val now = System.currentTimeMillis()
        val cards = runCatching { c.userDb.cards().wordCards(lang.code, word) }.getOrDefault(emptyList())
        // Only cards that are due, so an extra drill doesn't pull a card's schedule forward.
        val card = cards.firstOrNull { it.kind == kind.name && it.state != 0 && it.due <= now }
            ?: cards.firstOrNull { it.kind == CardKind.RECOGNITION.name && it.state != 0 && it.due <= now && mode == VoiceMode.LISTEN }
            ?: return
        val rating = when (v) {
            VoiceVerdict.RIGHT -> Fsrs.Rating.GOOD
            VoiceVerdict.CLOSE -> Fsrs.Rating.HARD
            else -> Fsrs.Rating.AGAIN
        }
        runCatching { c.review.rate(card, rating) }
    }

    private fun stopAll() {
        loop?.cancel(); loop = null
        holdAutoNext()
        pronJob?.cancel(); pronJob = null
        listener.stop()
        c.speaker.stop()
    }

    override fun onCleared() { stopAll() }

    // ---------------------------------------------------------------- Gemini

    /** Records you saying the answer again and asks Gemini how it sounded. */
    fun checkPronunciation() {
        holdAutoNext()
        val item = _state.value.item ?: return
        pronJob?.cancel()
        listener.stop(); c.speaker.stop()
        pronJob = viewModelScope.launch {
            val s = c.settings.settings.first()
            if (s.geminiKey.isBlank()) { _state.update { it.copy(pron = PronState.Error("Add a Gemini API key in Settings › Rehearse first.")) }; return@launch }
            _state.update { it.copy(pron = PronState.Recording()) }
            val wav = WavRecorder.record(onLevel = { l -> _state.update { st -> if (st.pron is PronState.Recording) st.copy(pron = PronState.Recording(l)) else st } })
            if (wav == null) { _state.update { it.copy(pron = PronState.Error("I didn't hear anything. Tap to try again.")) }; return@launch }
            _state.update { it.copy(pron = PronState.Checking) }
            val pinyin = item.pinyin ?: if (item.answer == Lang.ZH) runCatching { c.dictionary.pinyinOf(item.display) }.getOrNull() else null
            val out = runCatching { c.gemini.pronunciation(s.geminiKey, s.geminiModel, item.answer, item.display, pinyin, wav) }
            _state.update {
                it.copy(pron = out.fold({ p -> PronState.Result(p) }, { e -> PronState.Error(e.message ?: "Gemini couldn't check it.") }))
            }
        }
    }

    /** Sentences: Gemini grades the spoken translation like a Rehearse answer. */
    fun checkWithGemini() {
        holdAutoNext()
        val st = _state.value
        val item = st.item ?: return
        val heard = st.heard?.best ?: return
        viewModelScope.launch {
            val s = c.settings.settings.first()
            if (s.geminiKey.isBlank()) { _state.update { it.copy(aiError = "Add a Gemini API key in Settings › Rehearse first.") }; return@launch }
            _state.update { it.copy(aiLoading = true, aiError = null) }
            val out = runCatching {
                c.gemini.grade(
                    s.geminiKey, s.geminiModel, item.ask, item.askText,
                    listOf(GeminiClient.AnswerInput(item.answer, heard, item.accepted)),
                    strictAccents = false, strictTones = false,
                )[item.answer]
            }
            _state.update { it.copy(aiLoading = false, aiFeedback = out.getOrNull(), aiError = out.exceptionOrNull()?.message) }
        }
    }

    // ---------------------------------------------------------------- content

    /** Questions made so far this session, so the next ones take turns between types and languages. */
    private var made = 0

    /** One question's type and languages, taking turns. */
    /** The language pairs of this session, shuffled once so every pair comes up before any repeats. */
    private var pairs: List<Pair<Lang, Lang>> = emptyList()

    private fun plan(cfg: VoiceConfig): Triple<VoiceMode, Lang, Lang>? {
        if (pairs.isEmpty()) return null
        val i = made
        val modes = cfg.modes.ifEmpty { listOf(VoiceMode.SAY) }
        val mode = modes[i % modes.size]
        val (base, target) = pairs[(i / modes.size) % pairs.size]
        return Triple(mode, base, target)
    }

    /** First batch: the whole session, or 20 when it's endless (more are added as you go). */
    private suspend fun buildItems(cfg: VoiceConfig): List<VoiceItem> {
        made = 0
        pairs = cfg.pairsFor(c.settings.settings.first().activeLanguages).shuffled()
        pool = emptyList(); poolAt = 0
        retrying = false
        return makeItems(cfg, if (cfg.endless) BATCH else cfg.length)
    }

    /** Endless sessions: adds questions when a few are left. */
    private fun topUp() {
        val st = _state.value
        if (!st.config.endless || retrying || st.index < st.items.size - 3 || toppingUp) return
        toppingUp = true
        viewModelScope.launch {
            try {
                val more = runCatching { makeItems(st.config, BATCH) }.getOrDefault(emptyList())
                if (more.isNotEmpty()) _state.update { it.copy(items = it.items + more) }
            } finally {
                toppingUp = false
            }
        }
    }

    private var toppingUp = false
    /** Practising the missed ones: a fixed list, even in an endless session. */
    private var retrying = false

    /** Saved words or vocabulary words, shuffled; refilled when used up (endless sessions go round again). */
    private var pool: List<Any> = emptyList()
    private var poolAt = 0

    private suspend fun makeItems(cfg: VoiceConfig, count: Int): List<VoiceItem> {
        val s = c.settings.settings.first()
        val bases = cfg.basesFor(s.activeLanguages)
        val targets = cfg.targetsFor(s.activeLanguages)
        if (targets.isEmpty()) return emptyList()
        val out = ArrayList<VoiceItem>()
        var misses = 0
        while (out.size < count && misses < count * 4 + 20) {
            val (mode, base, target) = plan(cfg) ?: return out
            val item = when (cfg.source) {
                VoiceSource.VOCAB -> nextVocab(cfg, mode, base, target)
                VoiceSource.SAVED -> nextSaved(s, cfg, mode, base, targets)
                VoiceSource.SENTENCES -> nextSentence(mode, base, target, s.rehearseLevel)
            }
            if (item == null) { misses++; if (pool.isEmpty() && cfg.source != VoiceSource.SENTENCES) return out; continue }
            out += item
            made++
        }
        return out
    }

    private suspend fun <T : Any> nextFromPool(fill: suspend () -> List<T>): T? {
        if (poolAt >= pool.size) { pool = fill().shuffled(); poolAt = 0 }
        @Suppress("UNCHECKED_CAST")
        return (pool.getOrNull(poolAt++) as? T)
    }

    private suspend fun nextVocab(cfg: VoiceConfig, mode: VoiceMode, base: Lang, target: Lang): VoiceItem? {
        val w = nextFromPool {
            val all = _state.value.sets
            val sets = cfg.setId?.let { id -> all.filter { it.id == id } }?.ifEmpty { null } ?: all
            sets.flatMap { it.words }
        } ?: return null
        val tf = w.form(target)
        val bf = w.form(base)
        if (tf.isBlank() || bf.isBlank()) return null
        return make(mode, base, target, baseText = bf, baseAccepted = listOf(bf), targetText = tf, targetAccepted = listOf(tf),
            pinyinTarget = w.pinyin.takeIf { target == Lang.ZH }, pinyinBase = w.pinyin.takeIf { base == Lang.ZH }, sentence = false, review = null)
    }

    /** Saved words are practised in their own language, so the word picks the target. */
    private suspend fun nextSaved(s: app.diacritica.settings.AppSettings, cfg: VoiceConfig, mode: VoiceMode, base: Lang, targets: List<Lang>): VoiceItem? {
        val w = nextFromPool { runCatching { c.userDb.saved().allNow() }.getOrDefault(emptyList())
            .filter { Lang.fromCode(it.lang) in targets } } ?: return null
        val t = Lang.fromCode(w.lang) ?: return null
        val b = if (base != t) base else cfg.basesFor(s.activeLanguages).firstOrNull { it != t } ?: return null
        val r = runCatching { c.dictionary.lookup(w.word, t, listOf(t, b), s.script) }.getOrNull()
        val hw: WordInfo = r?.headword ?: WordInfo(t, w.word)
        val baseWords = r?.results?.firstOrNull { it.lang == b }?.words.orEmpty()
        if (mode != VoiceMode.ECHO && baseWords.isEmpty()) return null
        val targetWords = (listOf(hw) + r?.results?.firstOrNull { it.lang == t }?.words.orEmpty()).distinctBy { it.word }
        fun full(x: WordInfo) = x.article?.let { a -> if (a.endsWith("'")) a + x.word else "$a ${x.word}" } ?: x.word
        return make(
            mode, b, t,
            baseText = baseWords.firstOrNull()?.let { full(it) }.orEmpty(), baseAccepted = baseWords.map { it.word },
            targetText = full(hw), targetAccepted = targetWords.map { it.word },
            pinyinTarget = hw.pinyin, pinyinBase = baseWords.firstOrNull()?.pinyin, sentence = false, review = t to w.word,
        )
    }

    private suspend fun nextSentence(mode: VoiceMode, base: Lang, target: Lang, level: Int): VoiceItem? {
        val g = runCatching { c.sentences.next(Difficulty.band(level), listOf(base, target), minLangs = 2) }.getOrNull() ?: return null
        val b = g.texts[base].orEmpty()
        val tt = g.texts[target].orEmpty()
        if (tt.isEmpty() || (mode != VoiceMode.ECHO && b.isEmpty())) return null
        return make(mode, base, target, baseText = b.firstOrNull().orEmpty(), baseAccepted = b,
            targetText = tt.first(), targetAccepted = tt, pinyinTarget = null, pinyinBase = null, sentence = true, review = null)
    }

    private fun make(
        mode: VoiceMode, base: Lang, target: Lang, baseText: String, baseAccepted: List<String>, targetText: String, targetAccepted: List<String>,
        pinyinTarget: String?, pinyinBase: String?, sentence: Boolean, review: Pair<Lang, String>?,
    ): VoiceItem = when (mode) {
        VoiceMode.SAY -> VoiceItem(mode, base, base, baseText, target, targetAccepted, targetText, pinyinTarget, sentence, review)
        VoiceMode.LISTEN -> VoiceItem(mode, base, target, targetText, base, baseAccepted, baseText, pinyinBase, sentence, review)
        VoiceMode.ECHO -> VoiceItem(mode, base, target, targetText, target, listOf(targetText), targetText, pinyinTarget, sentence, review)
    }

    companion object {
        private const val BATCH = 20
    }
}
