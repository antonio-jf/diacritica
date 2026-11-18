package app.diacritica.ui.rehearse

import app.diacritica.rehearse.Difficulty
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

    fun ensureStarted() {
        if (external || _state.value.group != null || _state.value.empty) return
        // Android closed the app while you were typing: bring back the same sentence with your answers.
        val draft = c.drafts.getString(DRAFT_KEY, null) ?: return next()
        external = true
        viewModelScope.launch {
            try {
                if (!runCatching { restoreDraft(draft) }.getOrDefault(false)) { clearDraft(); next() }
            } finally {
                external = false
            }
        }
    }

    private suspend fun restoreDraft(json: String): Boolean {
        val o = org.json.JSONObject(json)
        val id = o.getLong("group")
        val prompt = Lang.fromCode(o.getString("prompt")) ?: return false
        val targets = o.getJSONArray("targets").let { a -> (0 until a.length()).mapNotNull { Lang.fromCode(a.getString(it)) } }
        val answersJson = o.getJSONObject("answers")
        val answers = answersJson.keys().asSequence().mapNotNull { k -> Lang.fromCode(k)?.let { it to answersJson.getString(k) } }.toMap()
        val (group, note) = loadGroup(id) ?: return false
        if (prompt !in group.texts.keys) return false
        val s = c.settings.settings.first()
        show(
            Pick(
                group, o.optString("source", "Tatoeba"), promptLangs = listOf(prompt), targets = targets.ifEmpty { null },
                note = note ?: o.optString("note").ifBlank { null }, chosenPrompt = prompt,
            ),
            s,
        )
        _state.update { st -> if (st.group?.id == id) st.copy(answers = st.answers + answers.filterKeys { it in st.targets }) else st }
        return true
    }

    /** Saves the sentence and what you've typed so far; cleared once the round is checked or skipped. */
    private fun saveDraft(st: RehearseUiState) {
        val g = st.group ?: return
        val p = st.promptLang ?: return
        if (st.submitted || st.answers.values.all { it.isBlank() }) { clearDraft(); return }
        val o = org.json.JSONObject()
            .put("group", g.id).put("prompt", p.code)
            .put("targets", org.json.JSONArray(st.targets.map { it.code }))
            .put("answers", org.json.JSONObject(st.answers.mapKeys { it.key.code }))
            .put("source", st.source).put("note", st.note ?: "")
        c.drafts.edit().putString(DRAFT_KEY, o.toString()).apply()
    }

    private fun clearDraft() { c.drafts.edit().remove(DRAFT_KEY).apply() }

    /**
     * "Rehearse this sentence" from the Reader: the story's sentence becomes the prompt in [promptLang]; the
     * story's other languages are the references. The current path stays as it is for the next sentence.
     */
    /**
     * A passage from one of your books (Books › Rehearse this): it's the prompt as written, in [lang]. The English
     * translation from a breakdown, if any, is kept as a reference; the other references are filled in as usual.
     */
    fun rehearseText(text: String, lang: Lang, source: String, english: String?) {
        external = true
        viewModelScope.launch {
            try {
                _state.update { RehearseUiState(loading = true, roundsDone = it.roundsDone) }
                val s = c.settings.settings.first()
                // Stored like a generated sentence so the archive and Weak spots can reload it.
                val id = -(System.currentTimeMillis())
                c.userDb.generatedRefs().save(GeneratedRef(id, lang.code, text))
                english?.takeIf { lang != Lang.EN && it.isNotBlank() }?.let { c.userDb.generatedRefs().save(GeneratedRef(id, Lang.EN.code, it)) }
                show(Pick(SentenceGroup(id, Difficulty.tag(s.rehearseLevel), mapOf(lang to listOf(text))), source, promptLangs = listOf(lang)), s)
            } finally {
                external = false
            }
        }
    }

    fun rehearseStorySentence(storyId: Long, idx: Int, promptLang: Lang) {
        external = true
        viewModelScope.launch {
            try {
                _state.update { RehearseUiState(loading = true, roundsDone = it.roundsDone) }
                val s = c.settings.settings.first()
                val (loaded, _) = loadGroup(storyGroupId(storyId, idx)) ?: run { next(); return@launch }
                val title = c.stories.load(storyId)?.title(Lang.EN) ?: "your reading"
                // A language the story isn't written in: translate the sentence into it first.
                var notice: String? = null
                val group = if (promptLang in loaded.texts.keys) loaded else {
                    val from = listOf(Lang.EN, Lang.ES).firstOrNull { it in loaded.texts.keys } ?: loaded.texts.keys.first()
                    val text = loaded.texts.getValue(from).first()
                    val viaGemini = if (s.geminiKey.isNotBlank() && c.online.isOnline()) {
                        runCatching { c.gemini.translate(s.geminiKey, s.geminiModel, from, text, listOf(promptLang))[promptLang] }.getOrNull()
                    } else null
                    val translated = viaGemini?.takeIf { it.isNotBlank() }
                        ?: runCatching { c.translator.translate(text, from, promptLang) }.getOrNull()?.takeIf { it.isNotBlank() }
                    if (translated != null) loaded.copy(texts = loaded.texts + (promptLang to listOf(translated)))
                    else { notice = "Couldn't translate the sentence into ${promptLang.englishName}, so it's shown in ${from.englishName}."; loaded }
                }
                show(Pick(group, "From “$title”", promptLangs = listOf(promptLang).filter { it in group.texts.keys }.ifEmpty { null }, notice = notice, chosenPrompt = promptLang), s)
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
        /** The prompt language was picked for this sentence (Reader › Rehearse from): it beats the toolbar's "From". */
        val chosenPrompt: Lang? = null,
    )

    private val recentIds = ArrayDeque<Long>()
    private val recentPrompts = ArrayDeque<String>()
    private fun remember(id: Long, prompt: String) {
        recentIds.addLast(id); if (recentIds.size > 40) recentIds.removeFirst()
        recentPrompts.addLast(prompt); if (recentPrompts.size > 20) recentPrompts.removeFirst()
    }

    /** Loads a new sentence from the current path; the prompt language rotates through your languages. */
    fun next() {
        clearDraft()
        viewModelScope.launch {
            _state.update { RehearseUiState(loading = true, roundsDone = it.roundsDone) }
            val s = c.settings.settings.first()
            val pick = try {
                // "Translate from" fixed: keep picking until the sentence exists in that language (a few tries).
                var p = pick(s)
                val from = fromLang(s)
                var tries = 0
                while (from != null && p != null && from !in p.group.texts.keys && s.rehearsePath != RehearsePath.WEAK_SPOTS && tries++ < 12) p = pick(s)
                p
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, empty = true, error = e.message ?: "Couldn't get a sentence.") }
                return@launch
            }
            if (pick == null) { _state.update { it.copy(loading = false, empty = true) }; return@launch }
            show(pick, s)
        }
    }

    private suspend fun pick(s: AppSettings): Pick? {
        if (s.rehearseLength > 1) passagePick(s)?.let { return it }
        val active = s.activeLanguages
        val everyday: suspend (String?) -> Pick? = { notice ->
            runCatching { c.sentences.next(band(s), active) }.getOrNull()?.let { Pick(it, "Tatoeba", notice = notice) }
        }
        return when (s.rehearsePath) {
            RehearsePath.EVERYDAY -> everyday(null)

            RehearsePath.MY_WORDS -> {
                val hit = runCatching { c.sentences.nextWithWord(myWords(active).filter { it.first in s.rehearseLanguages }, band(s)) }.getOrNull()
                if (hit == null) everyday("No sentence uses your saved or looked-up words yet, so here's an everyday one.")
                else Pick(hit.first, "Tatoeba", practice = hit.second, promptLangs = active.filter { it != hit.second.first })
            }

            RehearsePath.FROM_READING -> fromReading() ?: everyday("Open a story in the Reader or read a bit of a book first; until then, here's an everyday sentence.")

            RehearsePath.WEAK_SPOTS -> weakSpot() ?: everyday("No weak spots yet: every round so far was right. Here's an everyday sentence.")

            RehearsePath.REVERSE -> {
                val natives = active.filter { it == Lang.EN || it == Lang.ES }.ifEmpty { listOf(Lang.EN) }
                val learning = s.rehearseLanguages.filter { it !in natives }.ifEmpty { active.filter { it !in natives } }
                val g = runCatching { c.sentences.next(band(s), active) }.getOrNull() ?: return null
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
     * They only carry CEFR tags, so the difficulty step maps to those. Not bundled: they come as a data pack; Everyday is used until it's installed.
     */
    private suspend fun corpus(s: AppSettings, source: String, label: String): Pick? {
        val active = s.activeLanguages
        val g = if (c.corpora.available()) runCatching { c.corpora.next(band(s), active, minLangs = 1, source = source) }.getOrNull() else null
        if (g != null) return Pick(g.copy(id = CORPUS_BASE + g.id), label)
        val notice = if (c.corpora.available()) "No $source sentences at your levels yet. Here's an everyday sentence."
        else "News, Formal and Encyclopedic sentences come as a data pack (about 2 MB): Settings › Data packs. Here's an everyday sentence meanwhile."
        return c.sentences.next(band(s), active)?.let { Pick(it, "Tatoeba", notice = notice) }
    }

    /** A sentence you've already read in a Reader story (or any story, if none is opened yet). */
    private suspend fun fromReading(): Pick? {
        if (useBook()) bookPick(1)?.let { return it }
        val stories = c.stories.all().first()
        if (stories.isEmpty()) return bookPick(1)
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
        val from = fromLang(c.settings.settings.first())
        val rounds = c.userDb.rehearseRounds().weak().filter { it.groupId !in recentIds && (from == null || it.promptLang == from.code) }
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

    /**
     * Length 2–3: a short passage instead of one sentence.
     * - Gemini paths write a passage directly (see [generated]).
     * - From my reading takes consecutive sentences of a story you've opened.
     * - The sentence banks (Everyday, My words, Reverse, News, Formal, Encyclopedic) hold unrelated single sentences,
     *   so Gemini writes a passage in that path's style; without a key or offline, a Reader passage, else one sentence.
     * - Weak spots replays rounds as they were, so it returns null here.
     */
    private suspend fun passagePick(s: AppSettings): Pick? {
        val n = s.rehearseLength
        val active = s.activeLanguages
        val natives = active.filter { it == Lang.EN || it == Lang.ES }.ifEmpty { listOf(Lang.EN) }
        return when (s.rehearsePath) {
            RehearsePath.WEAK_SPOTS -> null
            RehearsePath.GRAMMAR, RehearsePath.CONVERSATION, RehearsePath.IDIOMS -> null   // generated() asks for n sentences
            RehearsePath.FROM_READING -> readingPassage(n)
            else -> {
                val style = when (s.rehearsePath) {
                    RehearsePath.NEWS -> "a short news report (who, what, where), neutral journalistic register"
                    RehearsePath.FORMAL -> "formal institutional register, like a parliamentary statement or an official letter"
                    RehearsePath.ENCYCLOPEDIC -> "a short encyclopedia-style explanation of a real topic (place, science, history, culture)"
                    else -> "everyday life (home, work, shopping, travel, friends, plans, feelings)"
                }
                val word = if (s.rehearsePath == RehearsePath.MY_WORDS)
                    runCatching { myWords(active).filter { it.first in s.rehearseLanguages }.randomOrNull() }.getOrNull() else null
                if (s.geminiKey.isNotBlank() && c.online.isOnline()) {
                    val focus = style + (word?.let { (l, w) -> ". In the ${l.englishName} text use the word \"$w\" (and its natural equivalents in the others)" } ?: "")
                    val group = runCatching { writePassage(s, focus, n) }.getOrNull()
                    if (group != null) {
                        val reverse = s.rehearsePath == RehearsePath.REVERSE
                        return Pick(
                            group, "Written by Gemini", practice = word, aiWritten = true,
                            promptLangs = when {
                                word != null -> active.filter { it != word.first }
                                reverse -> s.rehearseLanguages.filter { it !in natives }.ifEmpty { null }
                                else -> null
                            },
                            targets = if (reverse) natives else null,
                        )
                    }
                }
                val why = if (s.geminiKey.isBlank()) "Passages for this path are written by Gemini (add a key in Settings › Rehearse)"
                    else "Gemini couldn't write a passage right now"
                readingPassage(n)?.copy(notice = "$why, so here's a passage from your reading.")
                    ?: c.sentences.next(band(s), active)?.let { Pick(it, "Tatoeba", notice = "$why, so here's a single sentence.") }
            }
        }
    }

    /** [n] consecutive sentences of a story you've opened, up to where you've read. */
    /**
     * Whether "From my reading" takes this sentence from one of your books rather than a Reader story: books only
     * when Gemini can write the references (a book has just its own language), half the time when there are both.
     */
    private suspend fun useBook(): Boolean {
        val s = c.settings.settings.first()
        if (s.geminiKey.isBlank() || !c.online.isOnline()) return false
        val hasStories = c.stories.all().first().any { it.lastOpenedAt != null }
        return !hasStories || kotlin.random.Random.nextBoolean()
    }

    /** A passage of [n] sentences from the read part of one of your books (Books section). */
    private suspend fun bookPick(n: Int): Pick? {
        val s = c.settings.settings.first()
        if (s.geminiKey.isBlank()) return null
        val r = runCatching { c.bookshelf.readPassage(n, s.activeLanguages.toSet()) }.getOrNull() ?: return null
        // Stored like a generated sentence so the archive and Weak spots can reload it.
        val id = -(System.currentTimeMillis())
        c.userDb.generatedRefs().save(GeneratedRef(id, r.lang.code, r.text))
        return Pick(SentenceGroup(id, Difficulty.tag(s.rehearseLevel), mapOf(r.lang to listOf(r.text))), "From “${r.title}”", promptLangs = listOf(r.lang))
    }

    private suspend fun readingPassage(n: Int): Pick? {
        if (useBook()) bookPick(n)?.let { return it }
        val opened = c.stories.all().first().filter { it.lastOpenedAt != null }.ifEmpty { return bookPick(n) }
        repeat(6) {
            val story = opened.random()
            val loaded = c.stories.load(story.id) ?: return@repeat
            if (loaded.sentences.size < n) return@repeat
            val maxStart = (story.lastSentence.coerceIn(0, loaded.sentences.lastIndex) - n + 1).coerceIn(0, loaded.sentences.size - n)
            val start = (0..maxStart).random()
            val id = storyGroupId(story.id, start, n)
            if (id in recentIds && it < 5) return@repeat
            val group = storyPassage(loaded.sentences, start, n, id, story.level) ?: return@repeat
            return Pick(group, "From “${loaded.title(Lang.EN)}”")
        }
        return null
    }

    /** Joins sentences [start, start+n) of a story into one group (no spaces between Mandarin sentences). */
    private fun storyPassage(sentences: List<Map<Lang, String>>, start: Int, n: Int, id: Long, level: String): SentenceGroup? {
        if (start !in sentences.indices) return null
        val part = sentences.subList(start, (start + n).coerceAtMost(sentences.size))
        val langs = part.first().keys.filter { l -> part.all { it[l] != null } }
        val texts = langs.associateWith { l -> listOf(part.joinToString(if (l == Lang.ZH) "" else " ") { it[l]!!.trim() }) }
        return if (texts.size < 2) null else SentenceGroup(id, level, texts)
    }

    /** A passage in [focus]'s style, stored like other generated sentences (so Weak spots and the archive can reload it). */
    private suspend fun writePassage(s: AppSettings, focus: String, n: Int): SentenceGroup {
        val active = s.activeLanguages
        val natives = active.filter { it == Lang.EN || it == Lang.ES }.ifEmpty { listOf(Lang.EN) }
        val from = fromLang(s)
        val langs = active.filter { it in s.rehearseLanguages || it in natives || it == from }
        val level = Difficulty.band(s.rehearseLevel).cefrLabel
        val ex = c.gemini.generateExercise(s.geminiKey, s.geminiModel, GeminiClient.ExerciseKind.PASSAGE, focus, level, Difficulty.band(s.rehearseZhLevel).hsk, langs, recentPrompts.toList(), sentences = n)
        val id = -(System.currentTimeMillis())
        ex.texts.forEach { (l, t) -> c.userDb.generatedRefs().save(GeneratedRef(id, l.code, t)) }
        return SentenceGroup(id, Difficulty.tag(s.rehearseLevel), ex.texts.mapValues { (_, t) -> listOf(t) })
    }

    /** Grammar focus, Conversation and Idioms: Gemini writes the sentence in all your languages. */
    private suspend fun generated(s: AppSettings): Pick {
        if (s.geminiKey.isBlank()) throw IllegalStateException("This path needs a Gemini API key (Settings › Rehearse).")
        if (!c.online.isOnline()) throw IllegalStateException("This path writes new sentences online. You're offline: try Everyday, From my reading or Weak spots.")
        val active = s.activeLanguages
        val natives = active.filter { it == Lang.EN || it == Lang.ES }.ifEmpty { listOf(Lang.EN) }
        val from = fromLang(s)
        val langs = active.filter { it in s.rehearseLanguages || it in natives || it == from }
        val level = Difficulty.band(s.rehearseLevel).cefrLabel
        val kind = when (s.rehearsePath) {
            RehearsePath.GRAMMAR -> GeminiClient.ExerciseKind.GRAMMAR
            RehearsePath.CONVERSATION -> GeminiClient.ExerciseKind.CONVERSATION
            else -> GeminiClient.ExerciseKind.IDIOM
        }
        val topic = GrammarTopics.byKey(s.grammarFocus)
        val ex = c.gemini.generateExercise(
            s.geminiKey, s.geminiModel, kind, topic.instruction.takeIf { kind == GeminiClient.ExerciseKind.GRAMMAR },
            level, Difficulty.band(s.rehearseZhLevel).hsk, langs, recentPrompts.toList(), sentences = s.rehearseLength,
        )
        // Stored like AI references, so Weak spots can bring the sentence back later.
        val id = -(System.currentTimeMillis())
        ex.texts.forEach { (l, t) -> c.userDb.generatedRefs().save(GeneratedRef(id, l.code, t)) }
        ex.note?.let { c.userDb.generatedRefs().save(GeneratedRef(id, NOTE, it)) }
        val group = SentenceGroup(id, Difficulty.tag(s.rehearseLevel), ex.texts.mapValues { (_, t) -> listOf(t) })
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
            val raw = -id - 1
            val len = (raw / PASSAGE_STEP).toInt() + 1
            val n = raw % PASSAGE_STEP
            c.stories.load(n / STORY_STRIDE)?.let { st ->
                storyPassage(st.sentences, (n % STORY_STRIDE).toInt(), len, id, st.story.level)?.let { it to null }
            }
        }
    }

    /** Story sentence ids; a passage of [len] sentences adds (len - 1) × [PASSAGE_STEP] so it can be reloaded. */
    private fun storyGroupId(storyId: Long, idx: Int, len: Int = 1) = -(storyId * STORY_STRIDE + idx + 1 + (len - 1).coerceAtLeast(0) * PASSAGE_STEP)

    private suspend fun zhRefs(zh: List<String>, script: ScriptPreference): List<String> {
        val simp = zh.map { runCatching { c.dictionary.toSimplified(it) }.getOrDefault(it) }
        val preferred = zh.map { c.dictionary.toScript(it, script) }
        return (preferred + simp + zh).distinct()
    }

    /**
     * Sentence-bank difficulty: the Mandarin step when Mandarin is all you're practising, else the main one.
     * (The banks are levelled on the English anchor sentence, so one step has to pick them.)
     */
    private fun band(s: AppSettings): Difficulty.Band {
        val zhOnly = s.rehearseLanguages.isNotEmpty() && s.rehearseLanguages.all { it == Lang.ZH }
        return Difficulty.band(if (zhOnly) s.rehearseZhLevel else s.rehearseLevel)
    }

    fun setLevel(v: Int) { viewModelScope.launch { c.settings.setRehearseLevel(v) } }
    fun setZhLevel(v: Int) { viewModelScope.launch { c.settings.setRehearseZhLevel(v) } }
    fun setStrictAccents(v: Boolean) { viewModelScope.launch { c.settings.setStrictAccents(v) } }
    fun setStrictTones(v: Boolean) { viewModelScope.launch { c.settings.setStrictTones(v) } }
    fun setPinyinHint(v: Boolean) { viewModelScope.launch { c.settings.setPinyinHint(v) } }

    /** The fixed "Translate from" language, if it's still one of your languages. */
    private fun fromLang(s: AppSettings): Lang? = s.rehearseFrom?.takeIf { it in s.activeLanguages }

    fun setLength(n: Int) {
        viewModelScope.launch { c.settings.setRehearseLength(n); next() }
    }

    fun setFrom(l: Lang?) {
        viewModelScope.launch { c.settings.setRehearseFrom(l); next() }
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
        // "Translate from" fixed in the toolbar wins over the path's own choice when the sentence has that language.
        val from = p.chosenPrompt?.takeIf { it in group.texts.keys }
            ?: fromLang(s)?.takeIf { it in group.texts.keys && it != p.practice?.first }
        val promptLang = from
            ?: (if (p.targets == null && practiceLangs.size == 1) ordered.firstOrNull() else ordered.getOrNull(rotation++ % ordered.size.coerceAtLeast(1)))
            ?: group.texts.keys.firstOrNull() ?: Lang.EN
        val rawPrompt = group.texts[promptLang]?.firstOrNull() ?: group.texts.values.first().first()
        // Tatoeba mixes simplified and traditional Mandarin: show the prompt in the script chosen in Settings.
        val prompt = if (promptLang == Lang.ZH) c.dictionary.toScript(rawPrompt, s.script) else rawPrompt
        val targets = (p.targets ?: practiceLangs).filter { it != promptLang }
            .ifEmpty { active.filter { it != promptLang && (it == Lang.EN || it == Lang.ES) } }
            .ifEmpty { active.filter { it != promptLang } }
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


    fun setAnswer(lang: Lang, text: String) {
        _state.update { it.copy(answers = it.answers + (lang to text)) }
        saveDraft(_state.value)
    }

    fun submit() {
        val st = _state.value
        if (st.submitted) return
        val group = st.group ?: return
        clearDraft()
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
        private const val DRAFT_KEY = "rehearse"
        /** Ids of Gemini-written sentences are -(time in ms), so they never collide with story ids. */
        private const val GENERATED_BASE = 1_000_000_000_000L
        private const val STORY_STRIDE = 10_000L
        /** Below GENERATED_BASE and above any single story sentence id (story ids < 10 million). */
        private const val PASSAGE_STEP = 100_000_000_000L
        /** OPUS sentence ids (News, Formal, Encyclopedic) are offset so they never collide with Tatoeba ids. */
        private const val CORPUS_BASE = 10_000_000_000L
        private const val NOTE = "note"
    }
}
