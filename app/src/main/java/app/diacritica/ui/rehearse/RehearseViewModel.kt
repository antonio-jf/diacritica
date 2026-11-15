package app.diacritica.ui.rehearse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.data.user.GeneratedRef
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.GrammarTopics
import app.diacritica.settings.AppSettings
import app.diacritica.settings.RehearsePath
import app.diacritica.rehearse.LangFeedback
import app.diacritica.rehearse.RuleChecker
import app.diacritica.rehearse.SentenceGroup
import app.diacritica.rehearse.Verdict
import app.diacritica.data.user.RehearseRound
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RehearseUiState(
    val loading: Boolean = true,
    val group: SentenceGroup? = null,
    val promptLang: Lang? = null,
    val prompt: String = "",
    val targets: List<Lang> = emptyList(),
    /** Reference translations per target language (human or AI-written). */
    val references: Map<Lang, List<String>> = emptyMap(),
    val generated: Set<Lang> = emptySet(),
    val answers: Map<Lang, String> = emptyMap(),
    val submitted: Boolean = false,
    val checking: Boolean = false,
    val feedback: Map<Lang, LangFeedback> = emptyMap(),
    val aiError: String? = null,
    val addedToReview: Set<Lang> = emptySet(),
    val roundsDone: Int = 0,
    val empty: Boolean = false,
    /** "My words" mode: the looked-up word this sentence practises. */
    val practiceWord: Pair<Lang, String>? = null,
    /** "My words" found no sentence for your words, so an everyday sentence is shown. */
    val myWordsFallback: Boolean = false,
    /** Archive row of this round, once submitted. */
    val roundId: Long? = null,
    /** Why the path fell back to an everyday sentence. */
    val notice: String? = null,
    /** Context shown with the prompt: the situation, the grammar focus, or the earlier score. */
    val note: String? = null,
    /** Where the sentence came from, for the footer. */
    val source: String = "Tatoeba",
    val path: RehearsePath = RehearsePath.EVERYDAY,
    /** The path couldn't produce a sentence (offline, no key…). */
    val error: String? = null,
)

class RehearseViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RehearseUiState())
    val state: StateFlow<RehearseUiState> = _state.asStateFlow()
    private var rotation = 0

    fun ensureStarted() { if (_state.value.group == null && !_state.value.empty) next() }

    /** A sentence picked by a path, before the prompt language and targets are chosen. */
    private data class Pick(
        val group: SentenceGroup,
        val source: String,
        /** Languages the prompt may be in (null = any language of the sentence). */
        val promptLangs: List<Lang>? = null,
        /** Languages to answer in (null = your Rehearse languages minus the prompt's). */
        val targets: List<Lang>? = null,
        val practice: Pair<Lang, String>? = null,
        val note: String? = null,
        val notice: String? = null,
        /** All references were written by Gemini. */
        val aiWritten: Boolean = false,
    )

    private val recentIds = ArrayDeque<Long>()
    private val recentPrompts = ArrayDeque<String>()
    private fun remember(id: Long, prompt: String) {
        recentIds.addLast(id); if (recentIds.size > 40) recentIds.removeFirst()
        recentPrompts.addLast(prompt); if (recentPrompts.size > 20) recentPrompts.removeFirst()
    }

    /** Loads a new sentence from the current path; the prompt language rotates through your languages. */
    fun next() {
        viewModelScope.launch {
            _state.update { RehearseUiState(loading = true, roundsDone = it.roundsDone) }
            val s = c.settings.settings.first()
            val pick = try {
                pick(s)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, empty = true, error = e.message ?: "Couldn't get a sentence.") }
                return@launch
            }
            if (pick == null) { _state.update { it.copy(loading = false, empty = true) }; return@launch }
            show(pick, s)
        }
    }

    private suspend fun pick(s: AppSettings): Pick? {
        val active = s.activeLanguages
        val everyday: suspend (String?) -> Pick? = { notice ->
            runCatching { c.sentences.next(s.levels, active) }.getOrNull()?.let { Pick(it, "Tatoeba", notice = notice) }
        }
        return when (s.rehearsePath) {
            RehearsePath.EVERYDAY -> everyday(null)

            RehearsePath.MY_WORDS -> {
                val hit = runCatching { c.sentences.nextWithWord(myWords(active).filter { it.first in s.rehearseLanguages }, s.levels) }.getOrNull()
                if (hit == null) everyday("No sentence uses your saved or looked-up words yet, so here's an everyday one.")
                else Pick(hit.first, "Tatoeba", practice = hit.second, promptLangs = active.filter { it != hit.second.first })
            }

            RehearsePath.FROM_READING -> fromReading() ?: everyday("Open a story in the Reader first; until then, here's an everyday sentence.")

            RehearsePath.WEAK_SPOTS -> weakSpot() ?: everyday("No weak spots yet: every round so far was right. Here's an everyday sentence.")

            RehearsePath.REVERSE -> {
                val natives = active.filter { it == Lang.EN || it == Lang.ES }.ifEmpty { listOf(Lang.EN) }
                val learning = s.rehearseLanguages.filter { it !in natives }.ifEmpty { active.filter { it !in natives } }
                val g = runCatching { c.sentences.next(s.levels, active) }.getOrNull() ?: return null
                Pick(g, "Tatoeba", promptLangs = learning.filter { it in g.texts.keys }, targets = natives)
            }

            RehearsePath.LITERARY -> literary(s)
                ?: everyday("Literary sentences aren't built yet: run pipeline/build_literary.py on your computer (see README). Here's an everyday sentence.")

            RehearsePath.GRAMMAR, RehearsePath.CONVERSATION, RehearsePath.IDIOMS -> generated(s)
        }
    }

    /**
     * A sentence from a classic book, shown in its original language. Literature runs harder than Tatoeba,
     * so your levels shift up one step (A2 → B1, B2 → B2 and C1).
     */
    private suspend fun literary(s: AppSettings): Pick? {
        if (!c.literary.available()) return null
        val litLangs = c.literary.languages()
        val candidates = s.activeLanguages.filter { it in litLangs && s.rehearseLanguages.any { t -> t != it } }
        if (candidates.isEmpty()) return null
        val levels = buildSet {
            if ("A2" in s.levels || "B1" in s.levels) add("B1")
            if ("B1" in s.levels || "B2" in s.levels) add("B2")
            if ("B2" in s.levels) add("C1")
        }
        val lang = candidates[rotation++ % candidates.size]
        val sent = c.literary.next(lang, levels) ?: return null
        val group = SentenceGroup(LITERARY_BASE + sent.id, sent.level, mapOf(sent.lang to listOf(sent.text)))
        val noRefs = s.geminiKey.isBlank() || !c.online.isOnline()
        return Pick(
            group, "Project Gutenberg (public domain)", promptLangs = listOf(sent.lang), note = "— ${sent.credit}",
            notice = if (noRefs) "Reference translations for literary sentences are written by Gemini; without it, answers are saved but not checked." else null,
        )
    }

    /** A sentence you've already read in a Reader story (or any story, if none is opened yet). */
    private suspend fun fromReading(): Pick? {
        val stories = c.stories.all().first()
        if (stories.isEmpty()) return null
        val opened = stories.filter { it.lastOpenedAt != null }.ifEmpty { return null }
        repeat(6) {
            val story = opened.random()
            val loaded = c.stories.load(story.id) ?: return@repeat
            if (loaded.sentences.isEmpty()) return@repeat
            val maxIdx = story.lastSentence.coerceIn(0, loaded.sentences.lastIndex)
            val idx = (0..maxIdx).random()
            val id = storyGroupId(story.id, idx)
            if (id in recentIds && it < 5) return@repeat
            val texts = loaded.sentences[idx].mapValues { (_, t) -> listOf(t) }
            return Pick(SentenceGroup(id, story.level, texts), "From “${loaded.title(Lang.EN)}”")
        }
        return null
    }

    /** A round you got partly wrong: same sentence, same prompt language. */
    private suspend fun weakSpot(): Pick? {
        val rounds = c.userDb.rehearseRounds().weak().filter { it.groupId !in recentIds }
        if (rounds.isEmpty()) return null
        // Favour recent mistakes, but mix in older ones.
        val r = (rounds.take(15) + rounds.shuffled().take(5)).random()
        val (group, note) = loadGroup(r.groupId) ?: return null
        val when_ = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(r.at))
        return Pick(
            group, "Weak spot", promptLangs = listOfNotNull(Lang.fromCode(r.promptLang)),
            note = listOfNotNull("You had ${r.correct} of ${r.total} right on $when_.", note).joinToString(" "),
            aiWritten = r.groupId < 0 && -r.groupId >= GENERATED_BASE,
        )
    }

    /** Grammar focus, Conversation and Idioms: Gemini writes the sentence in all your languages. */
    private suspend fun generated(s: AppSettings): Pick {
        if (s.geminiKey.isBlank()) throw IllegalStateException("This path needs a Gemini API key (Settings › Rehearse).")
        if (!c.online.isOnline()) throw IllegalStateException("This path writes new sentences online. You're offline: try Everyday, From my reading or Weak spots.")
        val active = s.activeLanguages
        val natives = active.filter { it == Lang.EN || it == Lang.ES }.ifEmpty { listOf(Lang.EN) }
        val langs = active.filter { it in s.rehearseLanguages || it in natives }
        val level = s.levels.ifEmpty { setOf("B1") }.random()
        val kind = when (s.rehearsePath) {
            RehearsePath.GRAMMAR -> GeminiClient.ExerciseKind.GRAMMAR
            RehearsePath.CONVERSATION -> GeminiClient.ExerciseKind.CONVERSATION
            else -> GeminiClient.ExerciseKind.IDIOM
        }
        val topic = GrammarTopics.byKey(s.grammarFocus)
        val ex = c.gemini.generateExercise(
            s.geminiKey, s.geminiModel, kind, topic.instruction.takeIf { kind == GeminiClient.ExerciseKind.GRAMMAR },
            level, s.storyHsk, langs, recentPrompts.toList(),
        )
        // Stored like AI references, so Weak spots can bring the sentence back later.
        val id = -(System.currentTimeMillis())
        ex.texts.forEach { (l, t) -> c.userDb.generatedRefs().save(GeneratedRef(id, l.code, t)) }
        ex.note?.let { c.userDb.generatedRefs().save(GeneratedRef(id, NOTE, it)) }
        val group = SentenceGroup(id, level, ex.texts.mapValues { (_, t) -> listOf(t) })
        // Grammar focus: you write the structure, so the prompt is in English/Spanish and you answer in the others.
        val promptLangs = if (kind == GeminiClient.ExerciseKind.GRAMMAR) natives else null
        val note = if (kind == GeminiClient.ExerciseKind.GRAMMAR) "Focus: ${topic.label}" + (ex.note?.let { " · $it" } ?: "") else ex.note
        return Pick(group, "Written by Gemini", promptLangs = promptLangs, note = note, aiWritten = true)
    }

    /** Rebuilds a sentence from its id: Tatoeba (> 0), a Reader story, or one written by Gemini. */
    private suspend fun loadGroup(id: Long): Pair<SentenceGroup, String?>? = when {
        id >= LITERARY_BASE -> c.literary.byId(id - LITERARY_BASE)?.let { l ->
            SentenceGroup(id, l.level, mapOf(l.lang to listOf(l.text))) to "— ${l.credit}"
        }
        id > 0 -> c.sentences.byId(id)?.let { it to null }
        -id >= GENERATED_BASE -> {
            val refs = c.userDb.generatedRefs().forGroup(id)
            val texts = refs.mapNotNull { r -> Lang.fromCode(r.lang)?.let { it to listOf(r.text) } }.toMap()
            if (texts.size < 2) null else SentenceGroup(id, "", texts) to refs.firstOrNull { it.lang == NOTE }?.text
        }
        else -> {
            val n = -id - 1
            c.stories.load(n / STORY_STRIDE)?.let { st ->
                st.sentences.getOrNull((n % STORY_STRIDE).toInt())?.let { m -> SentenceGroup(id, st.story.level, m.mapValues { (_, t) -> listOf(t) }) to null }
            }
        }
    }

    private fun storyGroupId(storyId: Long, idx: Int) = -(storyId * STORY_STRIDE + idx + 1)

    /** Chooses the prompt language and targets, fills in references and shows the round. */
    private suspend fun show(p: Pick, s: AppSettings) {
        val group = p.group
        val active = s.activeLanguages
        val practiceLangs = s.rehearseLanguages
        // The prompt can be any active language with this sentence (or the path's choice), except the "My words" word's
        // language, and must leave at least one language to answer in. Your native languages (EN/ES) are preferred
        // when only one language is selected for practice.
        val allowed = (p.promptLangs ?: active).filter { it in group.texts.keys && it != p.practice?.first }
        val available = allowed.filter { pl -> (p.targets ?: practiceLangs).any { it != pl } }
        val ordered = if (p.targets == null && practiceLangs.size == 1) available.sortedBy { it !in setOf(Lang.EN, Lang.ES) } else available
        val promptLang = (if (p.targets == null && practiceLangs.size == 1) ordered.firstOrNull() else ordered.getOrNull(rotation++ % ordered.size.coerceAtLeast(1)))
            ?: group.texts.keys.firstOrNull() ?: Lang.EN
        val prompt = group.texts[promptLang]?.firstOrNull() ?: group.texts.values.first().first()
        val targets = (p.targets ?: practiceLangs).filter { it != promptLang }
        val refs = HashMap<Lang, List<String>>()
        targets.forEach { l -> group.texts[l]?.let { refs[l] = it } }
        // Tatoeba mixes traditional and simplified Mandarin: add simplified versions so either script matches.
        refs[Lang.ZH]?.let { zh ->
            val simp = zh.map { runCatching { c.dictionary.toSimplified(it) }.getOrDefault(it) }
            refs[Lang.ZH] = (simp + zh).distinct()
        }
        val generated = HashSet<Lang>()
        if (p.aiWritten) generated += targets
        if (group.id > 0) c.userDb.generatedRefs().forGroup(group.id).forEach { g ->
            val l = Lang.fromCode(g.lang)
            if (l != null && l in targets && l !in refs) { refs[l] = listOf(g.text); generated += l }
        }
        remember(group.id, prompt)
        _state.update {
            it.copy(loading = false, group = group, promptLang = promptLang, prompt = prompt, targets = targets,
                references = refs, generated = generated, empty = false, practiceWord = p.practice,
                myWordsFallback = p.notice != null, notice = p.notice, note = p.note, source = p.source, path = s.rehearsePath)
        }
        // Missing references: ask Gemini once, then cache (they're shown after you submit).
        val missing = targets.filter { it !in refs }
        if (missing.isNotEmpty() && s.geminiKey.isNotBlank() && c.online.isOnline()) {
            runCatching { c.gemini.translate(s.geminiKey, s.geminiModel, promptLang, prompt, missing) }.getOrNull()?.forEach { (l, t) ->
                if (group.id > 0) c.userDb.generatedRefs().save(GeneratedRef(group.id, l.code, t))
                _state.update { st -> if (st.group?.id == group.id) st.copy(references = st.references + (l to listOf(t)), generated = st.generated + l) else st }
            }
        }
    }

    fun setPath(p: RehearsePath) {
        viewModelScope.launch { c.settings.setRehearsePath(p); next() }
    }

    fun setGrammarFocus(key: String) {
        viewModelScope.launch { c.settings.setGrammarFocus(key); next() }
    }

    /** Saved words first, then recent searches (resolved to dictionary headwords where possible), shuffled. */
    private suspend fun myWords(active: List<Lang>): List<Pair<Lang, String>> {
        val saved = c.userDb.saved().allNow().mapNotNull { w -> Lang.fromCode(w.lang)?.let { it to w.word } }
        val history = c.userDb.history().recentNow(200).mapNotNull { h -> Lang.fromCode(h.lang)?.let { it to h.query } }
        val s = c.settings.settings.first()
        // Searches are stored as typed ("schlussel"); use the dictionary headword (Schlüssel) for matching.
        val resolved = history.shuffled().filter { it.first in active }.distinct().take(25).mapNotNull { (l, q) ->
            runCatching { c.dictionary.lookup(q, l, active, s.script) }.getOrNull()?.let { r -> r.headword?.word?.let { r.source to it } }
        }
        return (saved.shuffled().filter { it.first in active } + resolved).distinct().take(30)
    }

    /** Turns a language on/off for Rehearse (at least one stays on). */
    fun toggleRehearseLang(l: Lang) {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val cur = s.rehearseLanguages.toSet()
            val updated = if (l in cur) cur - l else cur + l
            if (updated.isEmpty()) return@launch
            c.settings.setRehearseLangs(updated)
            if (!_state.value.submitted) next()
        }
    }


    fun setAnswer(lang: Lang, text: String) = _state.update { it.copy(answers = it.answers + (lang to text)) }

    fun submit() {
        val st = _state.value
        if (st.submitted) return
        val group = st.group ?: return
        viewModelScope.launch {
            val s = c.settings.settings.first()
            // 1) Instant offline check.
            val rules = HashMap<Lang, LangFeedback>()
            for (l in st.targets) {
                val refs = st.references[l].orEmpty()
                val pinyin = if (l == Lang.ZH && refs.isNotEmpty()) refs.map { runCatching { c.dictionary.pinyinOf(it) }.getOrDefault("") }.filter { it.isNotBlank() } else emptyList()
                rules[l] = RuleChecker.check(l, st.answers[l].orEmpty(), refs, s.strictAccents, s.strictTones, pinyin)
                    .copy(referenceGenerated = l in st.generated)
            }
            _state.update { it.copy(submitted = true, feedback = rules, checking = s.geminiKey.isNotBlank(), roundsDone = it.roundsDone + 1) }
            val roundId = runCatching {
                c.userDb.rehearseRounds().insert(
                    RehearseRound(
                        groupId = group.id, promptLang = (st.promptLang ?: Lang.EN).code, prompt = st.prompt, level = group.level,
                        practiceWord = st.practiceWord?.let { "${it.second} (${it.first.code})" },
                        correct = countCorrect(rules.values), total = st.targets.size,
                        feedbackJson = json.encodeToString(ListSerializer(LangFeedback.serializer()), st.targets.mapNotNull { rules[it] }),
                    ),
                )
            }.getOrNull()
            _state.update { it.copy(roundId = roundId) }

            // 2) Full grammar feedback from Gemini (skips answers the rules already confirmed as exact matches).
            if (s.geminiKey.isBlank()) return@launch
            if (!c.online.isOnline()) {
                _state.update { it.copy(checking = false, aiError = "You're offline, so only the quick offline check ran.") }
                return@launch
            }
            val toAsk = st.targets.filter { rules[it]?.verdict != Verdict.CORRECT }
            if (toAsk.isEmpty()) { _state.update { it.copy(checking = false) }; return@launch }
            try {
                val ai = c.gemini.grade(
                    s.geminiKey, s.geminiModel, st.promptLang ?: Lang.EN, st.prompt,
                    toAsk.map { GeminiClient.AnswerInput(it, st.answers[it].orEmpty(), st.references[it].orEmpty()) },
                    s.strictAccents, s.strictTones,
                )
                _state.update { cur ->
                    val merged = cur.feedback.toMutableMap()
                    ai.forEach { (l, f) -> merged[l] = f.copy(reference = cur.references[l]?.firstOrNull(), referenceGenerated = l in cur.generated) }
                    cur.copy(feedback = merged, checking = false)
                }
                saveFeedback()
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, aiError = e.message ?: "The AI check failed.") }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun countCorrect(f: Collection<LangFeedback>) = f.count { it.verdict == Verdict.CORRECT || it.verdict == Verdict.ACCEPTABLE }

    /** Updates the archived round with the AI feedback. */
    private suspend fun saveFeedback() {
        val st = _state.value
        val id = st.roundId ?: return
        val list = st.targets.mapNotNull { st.feedback[it] }
        runCatching { c.userDb.rehearseRounds().updateFeedback(id, json.encodeToString(ListSerializer(LangFeedback.serializer()), list), countCorrect(list)) }
    }

    // ------------------------------------------------------------ archive

    private val archiveQuery = MutableStateFlow("")
    val archiveSearch: StateFlow<String> = archiveQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val archive: StateFlow<List<RehearseRound>> = archiveQuery
        .flatMapLatest { q -> if (q.isBlank()) c.userDb.rehearseRounds().all() else c.userDb.rehearseRounds().search(q.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setArchiveSearch(q: String) { archiveQuery.value = q }

    fun decode(round: RehearseRound): List<LangFeedback> =
        runCatching { json.decodeFromString(ListSerializer(LangFeedback.serializer()), round.feedbackJson) }.getOrDefault(emptyList())

    suspend fun round(id: Long): RehearseRound? = c.userDb.rehearseRounds().byId(id)

    fun deleteRound(id: Long) { viewModelScope.launch { c.userDb.rehearseRounds().delete(id) } }

    /** "Add to Review" from the archive. */
    fun addToReviewFrom(prompt: String, f: LangFeedback) {
        val answer = f.corrected?.takeIf { f.verdict != Verdict.CORRECT } ?: f.reference ?: f.answer
        viewModelScope.launch { c.review.addSentenceCard(f.lang, prompt, answer) }
    }

    /** Saves the prompt and the corrected (or reference) answer as a Review card. */
    fun addToReview(lang: Lang) {
        val st = _state.value
        val f = st.feedback[lang] ?: return
        val answer = f.corrected?.takeIf { f.verdict != Verdict.CORRECT } ?: f.reference ?: f.answer
        viewModelScope.launch {
            c.review.addSentenceCard(lang, st.prompt, answer)
            _state.update { it.copy(addedToReview = it.addedToReview + lang) }
        }
    }

    fun speakPrompt() {
        val st = _state.value
        val l = st.promptLang ?: return
        viewModelScope.launch { c.speaker.speak(st.prompt, l, c.settings.settings.first().speechRate) }
    }

    fun speak(lang: Lang, text: String) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate) }
    }

    suspend fun pinyinOf(text: String): String = runCatching { c.dictionary.pinyinOf(text) }.getOrDefault("")

    companion object {
        /** Ids of Gemini-written sentences are -(time in ms), so they never collide with story ids. */
        private const val GENERATED_BASE = 1_000_000_000_000L
        private const val STORY_STRIDE = 10_000L
        /** Literary sentence ids are offset so they never collide with Tatoeba ids. */
        private const val LITERARY_BASE = 10_000_000_000L
        private const val NOTE = "note"
    }
}
