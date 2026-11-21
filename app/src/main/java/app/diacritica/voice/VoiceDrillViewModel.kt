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
            val langs = (st.config.targetsFor(st.active) + st.config.base).distinct()
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
                for ((text, lang) in VoicePhrases.question(item, cfg.base)) {
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
            for ((text, lang) in VoicePhrases.feedback(result.verdict, item, cfg.base)) {
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

    private suspend fun buildItems(cfg: VoiceConfig): List<VoiceItem> {
        val s = c.settings.settings.first()
        val targets = cfg.targetsFor(s.activeLanguages)
        if (targets.isEmpty()) return emptyList()
        return when (cfg.source) {
            VoiceSource.VOCAB -> fromVocab(cfg, targets)
            VoiceSource.SAVED -> fromSaved(cfg, targets)
            VoiceSource.SENTENCES -> fromSentences(cfg, targets, s.rehearseLevel)
        }
    }

    private fun fromVocab(cfg: VoiceConfig, targets: List<Lang>): List<VoiceItem> {
        val sets = _state.value.sets.let { all -> cfg.setId?.let { id -> all.filter { it.id == id } }?.ifEmpty { null } ?: all }
        val words = sets.flatMap { it.words }.filter { w -> w.form(cfg.base).isNotBlank() }.shuffled()
        val out = ArrayList<VoiceItem>()
        for (w in words) {
            if (out.size >= cfg.length) break
            val t = targets[out.size % targets.size]
            val tf = w.form(t)
            if (tf.isBlank()) continue
            val bf = w.form(cfg.base)
            out += make(cfg.mode, cfg.base, t, baseText = bf, baseAccepted = listOf(bf), targetText = tf, targetAccepted = listOf(tf),
                pinyinTarget = w.pinyin.takeIf { t == Lang.ZH }, pinyinBase = w.pinyin.takeIf { cfg.base == Lang.ZH }, sentence = false, review = null)
        }
        return out
    }

    private suspend fun fromSaved(cfg: VoiceConfig, targets: List<Lang>): List<VoiceItem> {
        val s = c.settings.settings.first()
        val saved = c.userDb.saved().allNow().filter { w -> Lang.fromCode(w.lang) in targets }.shuffled()
        val out = ArrayList<VoiceItem>()
        for (w in saved) {
            if (out.size >= cfg.length) break
            val t = Lang.fromCode(w.lang) ?: continue
            val r = runCatching { c.dictionary.lookup(w.word, t, listOf(t, cfg.base), s.script) }.getOrNull()
            val hw: WordInfo = r?.headword ?: WordInfo(t, w.word)
            val baseWords = r?.results?.firstOrNull { it.lang == cfg.base }?.words.orEmpty()
            if (cfg.mode != VoiceMode.ECHO && baseWords.isEmpty()) continue
            val targetWords = (listOf(hw) + r?.results?.firstOrNull { it.lang == t }?.words.orEmpty()).distinctBy { it.word }
            fun full(x: WordInfo) = x.article?.let { a -> if (a.endsWith("'")) a + x.word else "$a ${x.word}" } ?: x.word
            out += make(
                cfg.mode, cfg.base, t,
                baseText = baseWords.firstOrNull()?.let { full(it) }.orEmpty(), baseAccepted = baseWords.map { it.word },
                targetText = full(hw), targetAccepted = targetWords.map { it.word },
                pinyinTarget = hw.pinyin, pinyinBase = baseWords.firstOrNull()?.pinyin, sentence = false, review = t to w.word,
            )
        }
        return out
    }

    private suspend fun fromSentences(cfg: VoiceConfig, targets: List<Lang>, level: Int): List<VoiceItem> {
        val out = ArrayList<VoiceItem>()
        var tries = 0
        while (out.size < cfg.length && tries < cfg.length * 4) {
            tries++
            val t = targets[out.size % targets.size]
            val g = runCatching { c.sentences.next(Difficulty.band(level), listOf(cfg.base, t), minLangs = 2) }.getOrNull() ?: continue
            val b = g.texts[cfg.base].orEmpty()
            val tt = g.texts[t].orEmpty()
            if (tt.isEmpty() || (cfg.mode != VoiceMode.ECHO && b.isEmpty())) continue
            out += make(cfg.mode, cfg.base, t, baseText = b.firstOrNull().orEmpty(), baseAccepted = b,
                targetText = tt.first(), targetAccepted = tt, pinyinTarget = null, pinyinBase = null, sentence = true, review = null)
        }
        return out
    }

    private fun make(
        mode: VoiceMode, base: Lang, target: Lang, baseText: String, baseAccepted: List<String>, targetText: String, targetAccepted: List<String>,
        pinyinTarget: String?, pinyinBase: String?, sentence: Boolean, review: Pair<Lang, String>?,
    ): VoiceItem = when (mode) {
        VoiceMode.SAY -> VoiceItem(mode, base, baseText, target, targetAccepted, targetText, pinyinTarget, sentence, review)
        VoiceMode.LISTEN -> VoiceItem(mode, target, targetText, base, baseAccepted, baseText, pinyinBase, sentence, review)
        VoiceMode.ECHO -> VoiceItem(mode, target, targetText, target, listOf(targetText), targetText, pinyinTarget, sentence, review)
    }
}
