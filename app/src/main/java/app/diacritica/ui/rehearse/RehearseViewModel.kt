package app.diacritica.ui.rehearse

import app.diacritica.data.ScriptPreference
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import app.diacritica.undo.deleteRehearseRound
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

    /** True while a sentence sent from elsewhere (the Reader) is being loaded, so opening Rehearse doesn't replace it. */
    private var external = false

    fun ensureStarted() { if (!external && _state.value.group == null && !_state.value.empty) next() }

    /**
     * "Rehearse this sentence" from the Reader: the story's sentence becomes the prompt in [promptLang]; the
     * story's other languages are the references. The current path stays as it is for the next sentence.
     */
    fun rehearseStorySentence(storyId: Long, idx: Int, promptLang: Lang) {
        external = true
        viewModelScope.launch {
            try {
                _state.update { RehearseUiState(loading = true, roundsDone = it.roundsDone) }
                val s = c.settings.settings.first()
                val (group, _) = loadGroup(storyGroupId(storyId, idx)) ?: run { next(); return@launch }
                val title = c.stories.load(storyId)?.title(Lang.EN) ?: "your reading"
                show(Pick(group, "From “$title”", promptLangs = listOf(promptLang).filter { it in group.texts.keys }.ifEmpty { null }), s)
            } finally {
                external = false
            }
        }
    }

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

            RehearsePath.NEWS -> corpus(s, "news", "Global Voices (CC BY 3.0)")
            RehearsePath.FORMAL -> corpus(s, "formal", "Europarl")
            RehearsePath.ENCYCLOPEDIC -> corpus(s, "encyclopedic", "Wikipedia translations (CC BY-SA)")

            RehearsePath.GRAMMAR, RehearsePath.CONVERSATION, RehearsePath.IDIOMS -> generated(s)
        }
    }

    /**
     * News, Formal and Encyclopedic: human-translated sentences from OPUS, built by pipeline/build_corpora.py.
     * These run harder than Tatoeba, so B2 also includes C1. Not bundled: they come as a data pack; Everyday is used until it's installed.
     */
    private suspend fun corpus(s: AppSettings, source: String, label: String): Pick? {
        val active = s.activeLanguages
        val levels = s.levels.ifEmpty { setOf("B1") } + (if ("B2" in s.levels) setOf("C1") else emptySet())
        val g = if (c.corpora.available()) runCatching { c.corpora.next(levels, active, minLangs = 1, source = source) }.getOrNull() else null
        if (g != null) return Pick(g.copy(id = CORPUS_BASE + g.id), label)
        val notice = if (c.corpora.available()) "No $source sentences at your levels yet. Here's an everyday sentence."
        else "News, Formal and Encyclopedic sentences come as a data pack (about 2 MB): Settings › Data packs. Here's an everyday sentence meanwhile."
        return c.sentences.next(s.levels, active)?.let { Pick(it, "Tatoeba", notice = notice) }
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
        id >= CORPUS_BASE -> runCatching { c.corpora.byId(id - CORPUS_BASE) }.getOrNull()?.let { it.copy(id = id) to null }
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

    private suspend fun zhRefs(zh: List<String>, script: ScriptPreference): List<String> {
        val simp = zh.map { runCatching { c.dictionary.toSimplified(it) }.getOrDefault(it) }
        val preferred = zh.map { c.dictionary.toScript(it, script) }
        return (preferred + simp + zh).distinct()
    }

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
        val rawPrompt = group.texts[promptLang]?.firstOrNull() ?: group.texts.values.first().first()
        // Tatoeba mixes simplified and traditional Mandarin: show the prompt in the script chosen in Settings.
        val prompt = if (promptLang == Lang.ZH) c.dictionary.toScript(rawPrompt, s.script) else rawPrompt
        val targets = (p.targets ?: practiceLangs).filter { it != promptLang }
        val refs = HashMap<Lang, List<String>>()
        targets.forEach { l -> group.texts[l]?.let { refs[l] = it } }
        // Tatoeba mixes traditional and simplified Mandarin: references in your script come first (that's the one
        // shown), and both scripts are kept so either matches.
        refs[Lang.ZH]?.let { zh -> refs[Lang.ZH] = zhRefs(zh, s.script) }
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

    /** Grammar focus on [key] (from the Grammar page's weak points), with a fresh sentence. */
    fun focusGrammar(key: String) {
        viewModelScope.launch {
            c.settings.setRehearsePath(RehearsePath.GRAMMAR)
            c.settings.setGrammarFocus(key)
            next()
        }
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
            if (!_state.value.submitted) retarget(s.activeLanguages.filter { it in updated })
        }
    }

    /**
     * Keeps the current sentence and only changes which languages you answer in: answers you've typed stay,
     * a new language gets its reference (from the sentence, the cache, or Gemini). A new sentence comes only
     * from Skip or Next sentence.
     */
    private suspend fun retarget(practice: List<Lang>) {
        val st = _state.value
        val group = st.group ?: return
        val prompt = st.promptLang ?: return
        if (st.path == RehearsePath.REVERSE) return          // Reverse always answers in English/Spanish
        val targets = practice.filter { it != prompt }
        if (targets.isEmpty()) return                         // only the prompt's language left: keep the fields
        val refs = st.references.filterKeys { it in targets }.toMutableMap()
        val generated = st.generated.filter { it in targets }.toMutableSet()
        targets.filter { it !in refs }.forEach { l ->
            group.texts[l]?.let { t ->
                refs[l] = if (l == Lang.ZH) zhRefs(t, c.settings.settings.first().script) else t
            }
        }
        if (group.id > 0) c.userDb.generatedRefs().forGroup(group.id).forEach { g ->
            val l = Lang.fromCode(g.lang)
            if (l != null && l in targets && l !in refs) { refs[l] = listOf(g.text); generated += l }
        }
        _state.update { it.copy(targets = targets, references = refs, generated = generated, answers = it.answers.filterKeys { k -> k in targets || it.answers[k]?.isNotBlank() == true }) }
        // A newly added language without a human reference: ask Gemini once and cache it.
        val missing = targets.filter { it !in refs }
        val s = c.settings.settings.first()
        if (missing.isNotEmpty() && s.geminiKey.isNotBlank() && c.online.isOnline()) {
            runCatching { c.gemini.translate(s.geminiKey, s.geminiModel, prompt, st.prompt, missing) }.getOrNull()?.forEach { (l, t) ->
                if (group.id > 0) c.userDb.generatedRefs().save(GeneratedRef(group.id, l.code, t))
                _state.update { cur -> if (cur.group?.id == group.id) cur.copy(references = cur.references + (l to listOf(t)), generated = cur.generated + l) else cur }
            }
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
            aiCheck(s, rules)
        }
    }

    /** "Check again" after the Gemini check failed (busy, offline, a limit): the offline result stays meanwhile. */
    fun retryAiCheck() {
        val st = _state.value
        if (!st.submitted || st.checking) return
        viewModelScope.launch {
            val s = c.settings.settings.first()
            if (!c.online.isOnline()) {
                _state.update { it.copy(aiError = "You're still offline, so only the quick offline check ran.") }
                return@launch
            }
            _state.update { it.copy(checking = true, aiError = null) }
            aiCheck(s, _state.value.feedback)
        }
    }

    /** Full grammar feedback from Gemini (skips answers the rules already confirmed as exact matches). */
    private suspend fun aiCheck(s: AppSettings, rules: Map<Lang, LangFeedback>) {
        val st = _state.value
        val toAsk = st.targets.filter { rules[it]?.verdict != Verdict.CORRECT }
        if (toAsk.isEmpty()) { _state.update { it.copy(checking = false) }; return }
        try {
            val ai = c.gemini.grade(
                s.geminiKey, s.geminiModel, st.promptLang ?: Lang.EN, st.prompt,
                toAsk.map { GeminiClient.AnswerInput(it, st.answers[it].orEmpty(), st.references[it].orEmpty()) },
                s.strictAccents, s.strictTones,
            )
            _state.update { cur ->
                val merged = cur.feedback.toMutableMap()
                ai.forEach { (l, f) -> merged[l] = f.copy(reference = cur.references[l]?.firstOrNull(), referenceGenerated = l in cur.generated) }
                cur.copy(feedback = merged, checking = false, aiError = null)
            }
            saveFeedback()
        } catch (e: Exception) {
            _state.update { it.copy(checking = false, aiError = e.message ?: "The AI check failed.") }
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

    /** True when some answer only got the offline check (Gemini failed, was offline, or had no key then). */
    fun needsAiCheck(feedback: List<LangFeedback>): Boolean = feedback.any { !it.byAi && it.verdict != Verdict.CORRECT }

    /**
     * Runs the Gemini check on an archived round's answers (the ones the offline check couldn't confirm) and
     * saves the result. Returns the updated round; throws with a readable message if it can't run.
     */
    suspend fun recheckRound(id: Long): RehearseRound {
        val r = c.userDb.rehearseRounds().byId(id) ?: throw IllegalStateException("This round no longer exists.")
        val s = c.settings.settings.first()
        if (s.geminiKey.isBlank()) throw IllegalStateException("Add a Gemini API key in Settings › Rehearse to check answers.")
        if (!c.online.isOnline()) throw IllegalStateException("You're offline. Connect to the internet and try again.")
        val feedback = decode(r)
        val toAsk = feedback.filter { !it.byAi && it.verdict != Verdict.CORRECT }
        if (toAsk.isEmpty()) return r
        // References: the sentence's own translations if it can still be loaded, else the one saved with the round.
        val group = runCatching { loadGroup(r.groupId)?.first }.getOrNull()
        val refs = toAsk.associate { f ->
            val t = group?.texts?.get(f.lang).orEmpty()
            f.lang to (if (f.lang == Lang.ZH && t.isNotEmpty()) zhRefs(t, s.script) else t).ifEmpty { listOfNotNull(f.reference) }
        }
        val promptLang = Lang.fromCode(r.promptLang) ?: Lang.EN
        val ai = c.gemini.grade(
            s.geminiKey, s.geminiModel, promptLang, r.prompt,
            toAsk.map { GeminiClient.AnswerInput(it.lang, it.answer, refs[it.lang].orEmpty()) },
            s.strictAccents, s.strictTones,
        )
        val merged = feedback.map { f ->
            ai[f.lang]?.copy(reference = f.reference ?: refs[f.lang]?.firstOrNull(), referenceGenerated = f.referenceGenerated) ?: f
        }
        val list = json.encodeToString(ListSerializer(LangFeedback.serializer()), merged)
        val correct = countCorrect(merged)
        c.userDb.rehearseRounds().updateFeedback(r.id, list, correct)
        return r.copy(feedbackJson = list, correct = correct)
    }

    fun deleteRound(id: Long) { viewModelScope.launch { c.deleteRehearseRound(id) } }

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

    // ------------------------------------------------------------------ hands-free

    /** Hands-free status for the screen: what's happening now, or null when off. */
    private val _handsFree = MutableStateFlow<String?>(null)
    val handsFree: StateFlow<String?> = _handsFree.asStateFlow()

    /** The language the screen should listen in now (it opens voice input), or null. */
    private val _listen = MutableStateFlow<Lang?>(null)
    val listen: StateFlow<Lang?> = _listen.asStateFlow()
    private val heard = Channel<String?>(Channel.CONFLATED)
    private var handsFreeJob: Job? = null

    /** What voice input returned (null if cancelled, which pauses hands-free). */
    fun onHeard(text: String?) { _listen.value = null; heard.trySend(text) }

    /**
     * Hands-free rounds until stopped: read the prompt aloud, listen for each language's answer,
     * check, read back each verdict and the right sentence, then the next round.
     */
    fun startHandsFree() {
        if (handsFreeJob?.isActive == true) return
        handsFreeJob = viewModelScope.launch {
            try {
                while (true) {
                    _handsFree.value = "Getting a sentence…"
                    val ready = _state.first { !it.loading }
                    if (ready.group == null || ready.empty) { _handsFree.value = null; return@launch }
                    if (ready.submitted) { nextAndWait(ready.group.id); continue }
                    val rate = c.settings.settings.first().speechRate
                    val promptLang = ready.promptLang ?: return@launch
                    _handsFree.value = "Listen…"
                    if (!c.speaker.speakAndWait(ready.prompt, promptLang, rate)) return@launch
                    for (l in ready.targets) {
                        _handsFree.value = "Say it in ${l.englishName}"
                        while (heard.tryReceive().isSuccess) { /* drop stale results */ }
                        _listen.value = l
                        val text = heard.receive() ?: return@launch // cancelled: stop
                        setAnswer(l, text)
                    }
                    _handsFree.value = "Checking…"
                    submit()
                    val checked = _state.first { it.submitted && !it.checking }
                    for (l in ready.targets) {
                        val f = checked.feedback[l] ?: continue
                        val verdict = when (f.verdict) {
                            Verdict.CORRECT -> "Correct."
                            Verdict.ACCEPTABLE -> "Almost."
                            Verdict.INCORRECT -> "Not quite."
                            Verdict.UNGRADED -> "Here's one way to say it."
                        }
                        _handsFree.value = "${l.englishName}: $verdict"
                        if (!c.speaker.speakAndWait(verdict, Lang.EN, rate)) return@launch
                        val right = f.corrected?.takeIf { f.verdict != Verdict.CORRECT } ?: f.reference
                        if (f.verdict != Verdict.CORRECT && !right.isNullOrBlank()) {
                            if (!c.speaker.speakAndWait(right, l, rate)) return@launch
                        }
                        delay(300)
                    }
                    delay(900)
                    nextAndWait(ready.group.id)
                }
            } finally {
                _listen.value = null
                _handsFree.value = null
            }
        }
    }

    /** Loads the next sentence and waits (up to 30 s) until it has replaced [previous]. */
    private suspend fun nextAndWait(previous: Long) {
        next()
        withTimeoutOrNull(30_000) { _state.first { !it.loading && (it.group?.id != previous || it.error != null || it.empty) } }
    }

    fun stopHandsFree() {
        handsFreeJob?.cancel()
        handsFreeJob = null
        c.speaker.stop()
        _listen.value = null
        _handsFree.value = null
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
        /** OPUS sentence ids (News, Formal, Encyclopedic) are offset so they never collide with Tatoeba ids. */
        private const val CORPUS_BASE = 10_000_000_000L
        private const val NOTE = "note"
    }
}
