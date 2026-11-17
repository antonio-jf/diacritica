package app.diacritica.ui.vocab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.Lang
import app.diacritica.data.ScriptPreference
import app.diacritica.data.user.SavedWord
import app.diacritica.undo.removeSavedWord
import app.diacritica.vocab.VocabCheck
import app.diacritica.vocab.VocabSet
import app.diacritica.vocab.VocabSets
import app.diacritica.vocab.VocabWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuizMode(val label: String) { CHOOSE("Choose"), TYPE("Type") }

/** One question of a mini test: the word, and (for Choose) four answers to pick from. */
data class QuizQuestion(val word: VocabWord, val options: List<String> = emptyList())

data class Quiz(
    val setId: String,
    val mode: QuizMode,
    val questions: List<QuizQuestion>,
    val index: Int = 0,
    val input: String = "",
    /** The answer given to the current question, once checked. */
    val answered: String? = null,
    val result: VocabCheck.Result? = null,
    val grades: List<VocabCheck.Grade> = emptyList(),
) {
    val done get() = index >= questions.size
    val current get() = questions.getOrNull(index)
    /** Right = 1, almost = ½. */
    val score get() = if (grades.isEmpty()) 0 else (grades.sumOf { when (it) { VocabCheck.Grade.RIGHT -> 2; VocabCheck.Grade.ALMOST -> 1; else -> 0 }.toInt() } * 50 / grades.size)
}

data class VocabUiState(
    val loading: Boolean = true,
    val sets: List<VocabSet> = emptyList(),
    /** The language you're learning the words in. */
    val lang: Lang = Lang.FR,
    /** The language the words are shown in for the test (and next to each word). */
    val from: Lang = Lang.EN,
    val langs: List<Lang> = emptyList(),
    val open: String? = null,
    val quiz: Quiz? = null,
    /** Best score per "setId:lang". */
    val best: Map<String, Int> = emptyMap(),
    val message: String? = null,
    val catalog: Boolean = false,
    /** Catalog ids (or "custom-…") Gemini is writing now. */
    val writing: Set<String> = emptySet(),
    val catalogError: String? = null,
)

class VocabularyViewModel(private val c: AppContainer, context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("vocabulary", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(VocabUiState())
    val state: StateFlow<VocabUiState> = _state.asStateFlow()
    private val app = context.applicationContext
    private val custom = app.diacritica.vocab.CustomVocabStore(java.io.File(context.filesDir, "vocab/custom.json"))

    val savedKeys: StateFlow<Set<String>> = c.userDb.saved().keys().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val sets = withContext(Dispatchers.IO) {
                val raw = runCatching { VocabSets.all(app) }.getOrDefault(emptyList()) + custom.load()
                raw.map { inScript(it, s.script) }
            }
            val langs = s.activeLanguages.ifEmpty { Lang.entries.toList() }
            val learning = langs.filter { it != Lang.EN && it != Lang.ES }
            val saved = prefs.getString("lang", null)?.let { Lang.fromCode(it) }?.takeIf { it in langs }
            val lang = saved ?: learning.firstOrNull() ?: langs.first()
            val from = prefs.getString("from", null)?.let { Lang.fromCode(it) }?.takeIf { it != lang } ?: if (lang == Lang.EN) Lang.ES else Lang.EN
            val best = prefs.all.filterKeys { it.startsWith("best:") }.mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix("best:") to it } }.toMap()
            _state.update { it.copy(loading = false, sets = sets, langs = langs, lang = lang, from = from, best = best) }
        }
    }

    /** Mandarin in the script chosen in Settings. */
    private suspend fun inScript(set: VocabSet, script: ScriptPreference): VocabSet =
        if (script == ScriptPreference.SIMPLIFIED_FIRST || script == ScriptPreference.SIMPLIFIED_ONLY) set
        else set.copy(words = set.words.map { w -> w.copy(forms = w.forms + (Lang.ZH to c.dictionary.toScript(w.form(Lang.ZH), script))) })

    // ------------------------------------------------ catalog

    fun openCatalog(open: Boolean) = _state.update { it.copy(catalog = open, catalogError = null) }

    /** Gemini writes the words of a catalog topic (or your own, when [topic] is null) in all six languages; kept afterwards. */
    fun addTopic(topic: app.diacritica.vocab.CatalogTopic?, ownTopic: String?, count: Int, advanced: Boolean) {
        val name = topic?.title ?: ownTopic?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val id = topic?.id ?: ("custom-" + name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40))
        if (id in _state.value.writing) return
        _state.update { it.copy(writing = it.writing + id, catalogError = null) }
        viewModelScope.launch {
            val s = c.settings.settings.first()
            val result = when {
                s.geminiKey.isBlank() -> Result.failure(app.diacritica.rehearse.GeminiClient.GeminiException("New sets are written by Gemini: add your API key in Settings."))
                !c.online.isOnline() -> Result.failure(app.diacritica.rehearse.GeminiClient.GeminiException("You're offline. Connect to add a set."))
                else -> runCatching {
                    app.diacritica.vocab.VocabCatalog.write(
                        c.gemini, s.geminiKey, s.geminiModel, id, name, topic?.hint, topic?.category ?: "Your topics", count, advanced,
                    ).copy(custom = true)
                }
            }
            result.onSuccess { set ->
                withContext(Dispatchers.IO) { custom.save(set) }
                val shown = inScript(set, s.script)
                _state.update { st -> st.copy(sets = st.sets.filter { it.id != id } + shown, writing = st.writing - id, message = "“${set.title}” added: ${set.words.size} words.") }
            }.onFailure { e ->
                _state.update { it.copy(writing = it.writing - id, catalogError = (e as? app.diacritica.rehearse.GeminiClient.GeminiException)?.message ?: "Couldn't write the set. Try again.") }
            }
        }
    }

    /** Removes a set you added (it can be added again from the catalog). */
    fun deleteSet(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { custom.delete(id) }
            _state.update { st -> st.copy(sets = st.sets.filter { it.id != id }, open = if (st.open == id) null else st.open) }
        }
    }

    fun setLang(l: Lang) {
        prefs.edit().putString("lang", l.code).apply()
        _state.update { it.copy(lang = l, from = if (it.from == l) (if (l == Lang.EN) Lang.ES else Lang.EN) else it.from) }
    }

    fun setFrom(l: Lang) {
        if (l == _state.value.lang) return
        prefs.edit().putString("from", l.code).apply()
        _state.update { it.copy(from = l) }
    }

    fun open(id: String?) = _state.update { it.copy(open = id, quiz = null, catalog = if (id != null) false else it.catalog) }

    fun set(id: String): VocabSet? = _state.value.sets.firstOrNull { it.id == id }

    // ------------------------------------------------ mini test

    fun startQuiz(id: String, mode: QuizMode, only: List<VocabWord>? = null) {
        val set = set(id) ?: return
        val lang = _state.value.lang
        val words = (only ?: set.words).shuffled()
        val questions = words.map { w ->
            if (mode == QuizMode.TYPE) QuizQuestion(w)
            else {
                val wrong = set.words.filter { it != w }.shuffled().take(3).map { it.form(lang) }
                QuizQuestion(w, (wrong + w.form(lang)).shuffled())
            }
        }
        _state.update { it.copy(quiz = Quiz(id, mode, questions)) }
    }

    fun setInput(t: String) = _state.update { st -> st.copy(quiz = st.quiz?.copy(input = t)) }

    /** Checks a typed answer, or the picked option. */
    fun answer(given: String) {
        val q = _state.value.quiz ?: return
        val cur = q.current ?: return
        if (q.answered != null) return
        val lang = _state.value.lang
        val result = if (q.mode == QuizMode.CHOOSE) {
            VocabCheck.Result(if (given == cur.word.form(lang)) VocabCheck.Grade.RIGHT else VocabCheck.Grade.WRONG)
        } else VocabCheck.check(given, cur.word, lang)
        _state.update { st -> st.copy(quiz = q.copy(answered = given, result = result, grades = q.grades + result.grade)) }
    }

    fun next() {
        val q = _state.value.quiz ?: return
        val nq = q.copy(index = q.index + 1, input = "", answered = null, result = null)
        _state.update { it.copy(quiz = nq) }
        if (nq.done) {
            val key = "${q.setId}:${_state.value.lang.code}"
            // Only a test of the whole set counts as its best score.
            if (q.questions.size == (set(q.setId)?.words?.size ?: -1) && nq.score > (_state.value.best[key] ?: -1)) {
                prefs.edit().putInt("best:$key", nq.score).apply()
                _state.update { it.copy(best = it.best + (key to nq.score)) }
            }
        }
    }

    fun missed(q: Quiz): List<VocabWord> = q.questions.zip(q.grades).filter { it.second != VocabCheck.Grade.RIGHT }.map { it.first.word }

    fun closeQuiz() = _state.update { it.copy(quiz = null) }

    // ------------------------------------------------ saving and listening

    fun toggleSaved(w: VocabWord) {
        val lang = _state.value.lang
        val word = VocabCheck.headword(w.form(lang), lang)
        viewModelScope.launch {
            if ("${lang.code}:$word" in savedKeys.value) c.removeSavedWord(lang.code, word)
            else save(lang, w)
        }
    }

    /** Saves [words] for Review (missed words after a test). */
    fun saveAll(words: List<VocabWord>) {
        val lang = _state.value.lang
        viewModelScope.launch {
            words.forEach { save(lang, it) }
            _state.update { it.copy(message = "${words.size} " + (if (words.size == 1) "word" else "words") + " added to Review.") }
        }
    }

    private suspend fun save(lang: Lang, w: VocabWord) {
        val s = c.settings.settings.first()
        val word = VocabCheck.headword(w.form(lang), lang)
        val title = _state.value.open?.let { set(it)?.title } ?: "Vocabulary"
        c.userDb.saved().save(SavedWord(lang = lang.code, word = word, note = "$title · ${w.form(Lang.EN)}"))
        runCatching { c.review.ensureCards(lang, word, s.activeLanguages, s.script) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun speak(lang: Lang, text: String) {
        viewModelScope.launch { c.speaker.speak(text, lang, c.settings.settings.first().speechRate) }
    }
}
