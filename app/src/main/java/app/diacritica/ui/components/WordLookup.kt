package app.diacritica.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.AppContainer
import app.diacritica.data.FalseFriendWarning
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.data.WordInfo
import app.diacritica.data.user.HistoryItem
import app.diacritica.data.user.SavedWord
import app.diacritica.data.ScriptPreference
import app.diacritica.ui.theme.Diacritica
import app.diacritica.undo.removeSavedWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A word open in the shared word sheet. [lang] is null while the language is still being detected. */
data class SheetWord(
    val lang: Lang?,
    val token: String,
    val sentence: String? = null,
    val loading: Boolean = true,
    val result: LookupResult? = null,
    val fetching: Boolean = false,
    val traditional: Boolean = false,
    val warnings: List<FalseFriendWarning> = emptyList(),
)

/**
 * Looks a word up for the shared word sheet, for screens that don't have their own lookup
 * (Vocabulary, Review, and "Look up in Diacritica" from other apps). Same steps as the Reader:
 * offline first, then a Wiktionary download if it's missing and online lookups are on.
 */
class WordLookup(
    private val c: AppContainer,
    private val scope: CoroutineScope,
    /** Where saved words came from, for Library's filter ("Vocabulary", "Review", "Other apps"). */
    private val source: String,
) {
    private val _word = MutableStateFlow<SheetWord?>(null)
    val word: StateFlow<SheetWord?> = _word.asStateFlow()

    val savedKeys: StateFlow<Set<String>> = c.userDb.saved().keys().map { it.toSet() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** [lang] null: detect it from the dictionary (text selected in another app). */
    fun open(rawToken: String, lang: Lang?, sentence: String? = null) {
        val token = clean(rawToken)
        if (token.isEmpty()) return
        _word.value = SheetWord(lang, token, sentence)
        scope.launch {
            val s = c.settings.settings.first()
            val trad = s.script == ScriptPreference.TRADITIONAL_FIRST || s.script == ScriptPreference.TRADITIONAL_ONLY
            val langs = (listOfNotNull(lang, Lang.EN) + s.activeLanguages).distinct()
            suspend fun run() = runCatching { c.dictionary.lookup(token, lang, langs, s.script) }.getOrNull()
            var r = run()
            val found = lang ?: r?.source
            _word.update { w -> if (w?.token == token) w.copy(lang = found ?: Lang.EN, loading = false, result = r, traditional = trad) else w }
            val cedictOnly = r != null && r.concepts.isNotEmpty() && r.concepts.all { c.dictionary.isCedictOnly(it) }
            if (found != null && (r == null || r.concepts.isEmpty() || cedictOnly) && s.onlineLookup) {
                _word.update { w -> if (w?.token == token) w.copy(fetching = true) else w }
                val outcome = runCatching { c.online.fetchWord(token, found, langs).first }.getOrNull()
                if (outcome == app.diacritica.data.online.OnlineLookup.Outcome.FOUND) r = run()
                _word.update { w -> if (w?.token == token) w.copy(fetching = false, result = r) else w }
            }
            val l = found ?: return@launch
            val words = buildList { add(l to token); r?.headword?.let { add(it.lang to it.word) } }
            val warnings = runCatching { c.falseFriends.warningsFor(words, s.activeLanguages.filter { it != l }, max = 2) }.getOrDefault(emptyList())
            _word.update { w -> if (w?.token == token) w.copy(warnings = warnings) else w }
            if (r != null && r.concepts.isNotEmpty()) {
                c.userDb.history().deleteSame(token, l.code)
                c.userDb.history().insert(HistoryItem(query = token, lang = l.code))
            }
        }
    }

    /**
     * The word at [offset] in [text]: letters (with apostrophes and hyphens inside a word) for alphabetic
     * languages; for Chinese, the longest dictionary word (up to 4 characters) that covers the tapped character.
     */
    fun openAt(text: String, offset: Int, lang: Lang?) {
        if (text.isEmpty()) return
        val i = offset.coerceIn(0, text.length - 1)
        val sentence = text.trim()
        if (app.diacritica.ui.theme.Han.isHan(text[i].code)) {
            scope.launch {
                val s = c.settings.settings.first()
                var best = text[i].toString()
                search@ for (len in 4 downTo 2) {
                    for (start in (i - len + 1)..i) {
                        if (start < 0 || start + len > text.length) continue
                        val sub = text.substring(start, start + len)
                        if (!sub.all { app.diacritica.ui.theme.Han.isHan(it.code) }) continue
                        val r = runCatching { c.dictionary.lookup(sub, Lang.ZH, listOf(Lang.ZH, Lang.EN), s.script) }.getOrNull()
                        if (r != null && r.concepts.isNotEmpty()) { best = sub; break@search }
                    }
                }
                open(best, Lang.ZH, sentence)
            }
            return
        }
        fun inWord(ch: Char) = ch.isLetterOrDigit()
        fun joiner(k: Int) = k in 1 until text.length - 1 && text[k] in "'’-" && inWord(text[k - 1]) && inWord(text[k + 1])
        var j = i
        if (!inWord(text[j])) { if (j > 0 && inWord(text[j - 1])) j-- else return }
        var a = j
        while (a > 0 && (inWord(text[a - 1]) || joiner(a - 1))) a--
        var b = j + 1
        while (b < text.length && (inWord(text[b]) || joiner(b))) b++
        var word = text.substring(a, b)
        // French/Italian elision: "l'école" → "école", "dell'acqua" → "acqua".
        if (lang == Lang.FR || lang == Lang.IT) word = word.replace(Regex("^\\p{L}{1,4}['’]"), "")
        open(word, lang, sentence)
    }

    fun dismiss() { _word.value = null }

    fun toggleSave(w: WordInfo) {
        scope.launch {
            val key = "${w.lang.code}:${w.word}"
            if (key in savedKeys.value) {
                c.removeSavedWord(w.lang.code, w.word)
            } else {
                val s = c.settings.settings.first()
                val ctx = _word.value?.sentence?.takeIf { it.isNotBlank() }
                c.userDb.saved().save(SavedWord(lang = w.lang.code, word = w.word, note = ctx ?: w.gloss, deck = source))
                c.review.ensureCards(w.lang, w.word, s.activeLanguages, s.script)
            }
        }
    }

    fun speak(w: WordInfo) {
        val text = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
        scope.launch { c.speaker.speak(text, w.lang, c.settings.settings.first().speechRate, false) }
    }

    companion object {
        private val articles = Regex(
            "^(der|die|das|den|dem|des|ein|eine|le|la|les|un|une|il|lo|gli|i|uno|el|los|las|unos|unas|the|a|an|to)\\s+",
            RegexOption.IGNORE_CASE,
        )

        /** "der Apfel" → "Apfel", "l'orange (f)" → "orange", quotes and dashes trimmed. */
        fun clean(raw: String): String {
            var t = raw.trim().replace(Regex("\\s*\\((m|f|m/f|pl)\\)$"), "")
            t = t.trim('\'', '’', '-', '«', '»', '“', '”', '„', '"', '—', '.', ',', ';', ':', '!', '?', '¿', '¡', '。', '，')
            t = t.replace(articles, "")
            t = t.replace(Regex("^(l|d|dell|all|nell|un)['’]", RegexOption.IGNORE_CASE), "")
            return t.trim()
        }
    }
}

/** The shared word sheet as a bottom sheet, driven by a [WordLookup]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordLookupSheet(
    lookup: WordLookup,
    onOpenDictionary: ((Lang, String) -> Unit)? = null,
    extraActions: @Composable () -> Unit = {},
) {
    val w by lookup.word.collectAsStateWithLifecycle()
    val saved by lookup.savedKeys.collectAsStateWithLifecycle()
    val word = w ?: return
    ModalBottomSheet(
        onDismissRequest = lookup::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Diacritica.colors.ground,
    ) {
        val lang = word.lang ?: Lang.EN
        val hw = word.result?.headword ?: WordInfo(lang, word.token)
        WordSheetBody(
            lang = lang, token = word.token, result = word.result, loading = word.loading, fetching = word.fetching,
            saved = "${hw.lang.code}:${hw.word}" in saved,
            onSpeak = lookup::speak, onToggleSave = lookup::toggleSave,
            traditional = word.traditional,
            onLemma = { l -> lookup.open(l, lang, word.sentence) },
            notes = { word.warnings.forEach { FalseFriendNote(it, Modifier.padding(top = 10.dp)) } },
            sentence = word.sentence,
            onOpenDictionary = onOpenDictionary?.let { open -> { l, s -> lookup.dismiss(); open(l, s) } },
            extraActions = extraActions,
        )
    }
}
