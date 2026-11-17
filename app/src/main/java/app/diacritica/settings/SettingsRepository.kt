package app.diacritica.settings

import app.diacritica.rehearse.Difficulty
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.diacritica.data.Lang
import app.diacritica.data.ScriptPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeId(val title: String, val blurb: String) {
    PAPER("Paper", "Light and editorial: cream paper, serif headwords, hairline dividers"),
    TILES("Tiles", "Soft tonal cards, rounded corners, friendly sans-serif"),
    MIDNIGHT("Midnight", "Dark and dense, amber accent, technical type"),
    INDEX("Index", "Swiss and high-contrast: black rules, bold grotesk"),
}

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

/** Layouts are chosen per section; the theme is always global. */
enum class DictionaryLayout(val label: String) { LIST("List"), CARDS("Cards"), GRID("Grid"), TABLE("Table") }
enum class RehearseLayout(val label: String) { TABS("Tabs per language"), STACKED("Stacked cards"), TILES("Score tiles"), REPORT("Report rows") }
/** How the Reader shows a long-pressed word. */
/** Pinyin above Chinese in the Reader: every word, only words with uncommon characters, or none. */
/** Bottom bar: Home plus up to this many sections. */
const val MAX_NAV_TABS = 4
val DEFAULT_NAV_TABS = listOf("dictionary", "reader", "rehearse", "review")

enum class PinyinMode { ALL, RARE, OFF }

enum class PopupLayout(val label: String) { SHEET("Bottom sheet"), FLOATING("Floating card"), TOOLTIP("Tooltip") }
enum class ReaderLayout(val label: String) {
    TOP_BOTTOM("Top / bottom"), SIDE_BY_SIDE("Side by side"), INTERLINEAR("Interlinear"), NUMBERED("Numbered split"),
}

/** Where Rehearse sentences come from. [online] paths need a Gemini key and the internet. */
enum class RehearsePath(val label: String, val blurb: String, val online: Boolean = false) {
    EVERYDAY("Everyday", "Everyday sentences from Tatoeba, with human translations"),
    MY_WORDS("My words", "Sentences that use words you saved or looked up"),
    FROM_READING("From my reading", "Sentences from the Reader stories you've opened"),
    WEAK_SPOTS("Weak spots", "Rounds you got partly wrong, to try again"),
    REVERSE("Reverse", "From a language you're learning into English or Spanish"),
    NEWS("News", "Global Voices articles, translated by people (OPUS)"),
    FORMAL("Formal", "European Parliament speeches and their official translations (no Mandarin)"),
    ENCYCLOPEDIC("Encyclopedic", "Translated Wikipedia articles"),
    GRAMMAR("Grammar focus", "New sentences built around one structure you choose", online = true),
    CONVERSATION("Conversation", "Natural spoken lines, with a short situation", online = true),
    IDIOMS("Idioms & sayings", "A common idiom or saying and its natural equivalents", online = true),
}

/** Translate section: ML Kit on the phone (offline after a one-time model download) or Gemini (online). */
enum class TranslateEngine(val label: String) { ON_DEVICE("On-device"), GEMINI("Gemini") }

/** "theme" = the theme's own accent, "alt1"/"alt2" = alternatives, "dynamic" = Material You. */
enum class AccentChoice(val label: String) { THEME("Theme default"), ALT1("Alternative 1"), ALT2("Alternative 2"), DYNAMIC("From wallpaper") }

data class AppSettings(
    val onboarded: Boolean = false,
    val theme: ThemeId = ThemeId.PAPER,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentChoice = AccentChoice.THEME,
    val textScale: Float = 1f,
    /** Extra size for Chinese characters, on top of [textScale]. */
    val hanScale: Float = 1.15f,
    val languageColors: Boolean = false,
    val languageOrder: List<Lang> = Lang.defaultOrder,
    val enabled: Set<Lang> = Lang.defaultOrder.toSet(),
    /** null = auto-detect */
    val sourceLang: Lang? = Lang.EN,
    val script: ScriptPreference = ScriptPreference.SIMPLIFIED_FIRST,
    val dictionaryLayout: DictionaryLayout = DictionaryLayout.LIST,
    val speechRate: Float = 1f,
    /** Download words that are missing from the offline dictionary, and save them. */
    val onlineLookup: Boolean = true,
    /** New review cards introduced per day. */
    val newCardsPerDay: Int = 20,
    // Rehearse
    val geminiKey: String = "",
    val geminiModel: String = app.diacritica.rehearse.GeminiClient.DEFAULT_MODEL,
    val strictAccents: Boolean = true,
    val strictTones: Boolean = true,
    val rehearseLayout: RehearseLayout = RehearseLayout.TABS,
    val levels: Set<String> = setOf("A2", "B1", "B2"),
    val pinyinHint: Boolean = false,
    /** Also make review cards from words looked up in the Dictionary (not only starred ones). */
    val reviewFromHistory: Boolean = true,
    /** Rehearse sentence source: everyday (any sentence) or my words (sentences using words you looked up). */
    val rehearseMyWords: Boolean = false,
    /** Rehearse path; defaults to My words if that was switched on before paths existed. */
    val rehearsePath: RehearsePath = RehearsePath.EVERYDAY,
    /** Grammar focus path: the structure to practise (a key of GrammarTopics). */
    val grammarFocus: String = "mixed",
    val translateEngine: TranslateEngine = TranslateEngine.ON_DEVICE,
    /** Languages to translate into in Rehearse (empty = all active languages). */
    val rehearseLangs: Set<Lang> = emptySet(),
    /** Sentences per Rehearse round: 1, or a short passage of 2–3. */
    val rehearseLength: Int = 1,
    /** Rehearse difficulty, 1–10 (see rehearse/Difficulty.kt), for the European languages… */
    val rehearseLevel: Int = 5,
    /** …and for Mandarin. */
    val rehearseZhLevel: Int = 3,
    /** Rehearse prompts only in this language (null = rotate through your languages). */
    val rehearseFrom: Lang? = null,
    // Reader
    val readerLayout: ReaderLayout = ReaderLayout.TOP_BOTTOM,
    val readerLangA: Lang = Lang.FR,
    val readerLangB: Lang = Lang.ES,
    val readerPinyin: Boolean = true,
    val readerPinyinMode: PinyinMode = PinyinMode.RARE,
    /** In RARE mode, characters outside this many most common ones get pinyin. */
    val readerPinyinTop: Int = 1000,
    val readerPopup: PopupLayout = PopupLayout.SHEET,
    /** Top/bottom layouts: share of the height for the top pane (dragged with the divider). */
    val readerSplit: Float = 0.5f,
    /** Books: reading text size (sp), line spacing (× size) and serif or sans-serif text. */
    val bookTextSize: Int = 19,
    val bookLineSpacing: Float = 1.6f,
    val bookSerif: Boolean = true,
    /** Reader: a tap looks up the word (long-press highlights the sentence) instead of the reverse. */
    val readerTapLookup: Boolean = false,
    /** Sections in the bottom bar after Home, in order (routes: dictionary, reader, rehearse, review, tests…). */
    val navTabs: List<String> = DEFAULT_NAV_TABS,
    /** Reader: shade words you're learning (saved / in Review) and words you know (mature cards). */
    val readerShowMyWords: Boolean = true,
    val storyLevel: String = "B1",
    val storyHsk: Int = 3,
) {
    val activeLanguages: List<Lang> get() = languageOrder.filter { it in enabled }

    /** Rehearse targets, in the user's order. */
    val rehearseLanguages: List<Lang> get() = activeLanguages.filter { rehearseLangs.isEmpty() || it in rehearseLangs }.ifEmpty { activeLanguages }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = stringPreferencesKey("theme")
        val mode = stringPreferencesKey("mode")
        val accent = stringPreferencesKey("accent")
        val textScale = floatPreferencesKey("text_scale")
        val hanScale = floatPreferencesKey("han_scale")
        val languageColors = booleanPreferencesKey("language_colors")
        val order = stringPreferencesKey("language_order")
        val enabled = stringPreferencesKey("languages_enabled")
        val source = stringPreferencesKey("source_lang")
        val script = stringPreferencesKey("script")
        val dictLayout = stringPreferencesKey("layout_dictionary")
        val speechRate = floatPreferencesKey("speech_rate")
        val onlineLookup = booleanPreferencesKey("online_lookup")
        val pending = stringSetPreferencesKey("pending_words")
        val newPerDay = intPreferencesKey("new_cards_per_day")
        val geminiKey = stringPreferencesKey("gemini_key")
        val geminiModel = stringPreferencesKey("gemini_model")
        val strictAccents = booleanPreferencesKey("strict_accents")
        val strictTones = booleanPreferencesKey("strict_tones")
        val rehearseLayout = stringPreferencesKey("layout_rehearse")
        val levels = stringSetPreferencesKey("rehearse_levels")
        val pinyinHint = booleanPreferencesKey("pinyin_hint")
        val reviewFromHistory = booleanPreferencesKey("review_from_history")
        val rehearseMyWords = booleanPreferencesKey("rehearse_my_words")
        val rehearsePath = stringPreferencesKey("rehearse_path")
        val grammarFocus = stringPreferencesKey("grammar_focus")
        val translateEngine = stringPreferencesKey("translate_engine")
        val historySyncedAt = longPreferencesKey("history_synced_at")
        val rehearseLangs = stringSetPreferencesKey("rehearse_langs")
        val rehearseLength = intPreferencesKey("rehearse_length")
        val rehearseLevel = intPreferencesKey("rehearse_level")
        val rehearseZhLevel = intPreferencesKey("rehearse_zh_level")
        val rehearseFrom = stringPreferencesKey("rehearse_from")
        val readerLayout = stringPreferencesKey("layout_reader")
        val readerLangA = stringPreferencesKey("reader_lang_a")
        val readerLangB = stringPreferencesKey("reader_lang_b")
        val readerPinyin = booleanPreferencesKey("reader_pinyin")
        val readerPinyinMode = stringPreferencesKey("reader_pinyin_mode")
        val readerPinyinTop = intPreferencesKey("reader_pinyin_top")
        val readerPopup = stringPreferencesKey("layout_word_popup")
        val readerSplit = floatPreferencesKey("reader_split")
        val bookTextSize = intPreferencesKey("book_text_size")
        val bookLineSpacing = floatPreferencesKey("book_line_spacing")
        val bookSerif = booleanPreferencesKey("book_serif")
        val readerTapLookup = booleanPreferencesKey("reader_tap_lookup")
        val navTabs = stringPreferencesKey("nav_tabs")
        val readerShowMyWords = booleanPreferencesKey("reader_show_my_words")
        val storyLevel = stringPreferencesKey("story_level")
        val storyHsk = intPreferencesKey("story_hsk")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        val order = p[K.order]?.split(',')?.mapNotNull { Lang.fromCode(it) }
            ?.let { saved -> saved + Lang.defaultOrder.filter { it !in saved } } ?: d.languageOrder
        AppSettings(
            onboarded = p[K.onboarded] ?: false,
            theme = enumOr(p[K.theme], d.theme),
            mode = enumOr(p[K.mode], d.mode),
            accent = enumOr(p[K.accent], d.accent),
            textScale = p[K.textScale] ?: d.textScale,
            hanScale = p[K.hanScale] ?: d.hanScale,
            languageColors = p[K.languageColors] ?: d.languageColors,
            languageOrder = order,
            enabled = p[K.enabled]?.split(',')?.mapNotNull { Lang.fromCode(it) }?.toSet()?.ifEmpty { null } ?: d.enabled,
            sourceLang = when (val s = p[K.source]) { null -> d.sourceLang; "auto" -> null; else -> Lang.fromCode(s) },
            script = enumOr(p[K.script], d.script),
            dictionaryLayout = enumOr(p[K.dictLayout], d.dictionaryLayout),
            speechRate = p[K.speechRate] ?: d.speechRate,
            onlineLookup = p[K.onlineLookup] ?: d.onlineLookup,
            newCardsPerDay = p[K.newPerDay] ?: d.newCardsPerDay,
            geminiKey = p[K.geminiKey] ?: d.geminiKey,
            // A retired model saved earlier (gemini-3.5-flash is now forwarded to 3.6 Flash and its small quota) → default.
            geminiModel = p[K.geminiModel]?.ifBlank { null }?.takeIf { it !in app.diacritica.rehearse.GeminiClient.RETIRED } ?: d.geminiModel,
            strictAccents = p[K.strictAccents] ?: d.strictAccents,
            strictTones = p[K.strictTones] ?: d.strictTones,
            rehearseLayout = enumOr(p[K.rehearseLayout], d.rehearseLayout),
            levels = p[K.levels]?.ifEmpty { null } ?: d.levels,
            pinyinHint = p[K.pinyinHint] ?: d.pinyinHint,
            reviewFromHistory = p[K.reviewFromHistory] ?: d.reviewFromHistory,
            rehearseMyWords = p[K.rehearseMyWords] ?: d.rehearseMyWords,
            rehearsePath = p[K.rehearsePath]?.let { enumOr(it, d.rehearsePath) }
                ?: if (p[K.rehearseMyWords] == true) RehearsePath.MY_WORDS else d.rehearsePath,
            grammarFocus = p[K.grammarFocus] ?: d.grammarFocus,
            translateEngine = enumOr(p[K.translateEngine], d.translateEngine),
            rehearseLangs = p[K.rehearseLangs]?.mapNotNull { Lang.fromCode(it) }?.toSet() ?: d.rehearseLangs,
            rehearseLength = (p[K.rehearseLength] ?: d.rehearseLength).coerceIn(1, 3),
            // Upgrading from the A2/B1/B2 chips and the Story HSK setting: start from what was chosen there.
            rehearseLevel = (p[K.rehearseLevel] ?: p[K.levels]?.let { Difficulty.fromCefr(it) } ?: d.rehearseLevel).coerceIn(1, 10),
            rehearseZhLevel = (p[K.rehearseZhLevel] ?: p[K.storyHsk]?.let { Difficulty.fromHsk(it) } ?: d.rehearseZhLevel).coerceIn(1, 10),
            rehearseFrom = p[K.rehearseFrom]?.let { Lang.fromCode(it) },
            readerLayout = enumOr(p[K.readerLayout], d.readerLayout),
            readerLangA = p[K.readerLangA]?.let { Lang.fromCode(it) } ?: d.readerLangA,
            readerLangB = p[K.readerLangB]?.let { Lang.fromCode(it) } ?: d.readerLangB,
            readerPinyin = p[K.readerPinyin] ?: d.readerPinyin,
            // Before the mode existed there was only on/off: keep "off" for people who turned it off.
            readerPinyinMode = p[K.readerPinyinMode]?.let { enumOr(it, d.readerPinyinMode) }
                ?: if (p[K.readerPinyin] == false) PinyinMode.OFF else d.readerPinyinMode,
            readerPinyinTop = p[K.readerPinyinTop] ?: d.readerPinyinTop,
            readerPopup = enumOr(p[K.readerPopup], d.readerPopup),
            readerSplit = (p[K.readerSplit] ?: d.readerSplit).coerceIn(0.2f, 0.8f),
            bookTextSize = (p[K.bookTextSize] ?: d.bookTextSize).coerceIn(13, 32),
            bookLineSpacing = (p[K.bookLineSpacing] ?: d.bookLineSpacing).coerceIn(1.2f, 2.2f),
            bookSerif = p[K.bookSerif] ?: d.bookSerif,
            readerTapLookup = p[K.readerTapLookup] ?: d.readerTapLookup,
            navTabs = p[K.navTabs]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(MAX_NAV_TABS) ?: d.navTabs,
            readerShowMyWords = p[K.readerShowMyWords] ?: d.readerShowMyWords,
            storyLevel = p[K.storyLevel] ?: d.storyLevel,
            storyHsk = p[K.storyHsk] ?: d.storyHsk,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    suspend fun finishOnboarding(theme: ThemeId) = context.dataStore.edit {
        it[K.theme] = theme.name; it[K.onboarded] = true
    }
    suspend fun setTheme(v: ThemeId) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setMode(v: ThemeMode) = context.dataStore.edit { it[K.mode] = v.name }
    suspend fun setAccent(v: AccentChoice) = context.dataStore.edit { it[K.accent] = v.name }
    suspend fun setTextScale(v: Float) = context.dataStore.edit { it[K.textScale] = v }
    suspend fun setHanScale(v: Float) = context.dataStore.edit { it[K.hanScale] = v }
    suspend fun setLanguageColors(v: Boolean) = context.dataStore.edit { it[K.languageColors] = v }
    suspend fun setLanguageOrder(v: List<Lang>) = context.dataStore.edit { it[K.order] = v.joinToString(",") { l -> l.code } }
    suspend fun setEnabled(v: Set<Lang>) = context.dataStore.edit { it[K.enabled] = v.joinToString(",") { l -> l.code } }
    suspend fun setSourceLang(v: Lang?) = context.dataStore.edit { it[K.source] = v?.code ?: "auto" }
    suspend fun setScript(v: ScriptPreference) = context.dataStore.edit { it[K.script] = v.name }
    suspend fun setDictionaryLayout(v: DictionaryLayout) = context.dataStore.edit { it[K.dictLayout] = v.name }
    suspend fun setSpeechRate(v: Float) = context.dataStore.edit { it[K.speechRate] = v }
    suspend fun setNewCardsPerDay(v: Int) = context.dataStore.edit { it[K.newPerDay] = v }
    suspend fun setOnlineLookup(v: Boolean) = context.dataStore.edit { it[K.onlineLookup] = v }

    /** Words searched while offline, as "lang:word" ("auto:word" when auto-detecting). */
    val pendingWords: Flow<Set<String>> = context.dataStore.data.map { it[K.pending] ?: emptySet() }
    suspend fun addPending(key: String) = context.dataStore.edit { it[K.pending] = (it[K.pending] ?: emptySet()) + key }
    suspend fun removePending(key: String) = context.dataStore.edit { it[K.pending] = (it[K.pending] ?: emptySet()) - key }

    suspend fun setGeminiKey(v: String) = context.dataStore.edit { it[K.geminiKey] = v.trim() }
    suspend fun setGeminiModel(v: String) = context.dataStore.edit { it[K.geminiModel] = v.trim() }
    suspend fun setStrictAccents(v: Boolean) = context.dataStore.edit { it[K.strictAccents] = v }
    suspend fun setStrictTones(v: Boolean) = context.dataStore.edit { it[K.strictTones] = v }
    suspend fun setRehearseLayout(v: RehearseLayout) = context.dataStore.edit { it[K.rehearseLayout] = v.name }
    suspend fun setLevels(v: Set<String>) = context.dataStore.edit { it[K.levels] = v }
    suspend fun setReviewFromHistory(v: Boolean) = context.dataStore.edit { it[K.reviewFromHistory] = v }
    suspend fun setReaderLayout(v: ReaderLayout) = context.dataStore.edit { it[K.readerLayout] = v.name }
    suspend fun setReaderLangs(a: Lang, b: Lang) = context.dataStore.edit { it[K.readerLangA] = a.code; it[K.readerLangB] = b.code }
    suspend fun setReaderPinyin(v: Boolean) = context.dataStore.edit { it[K.readerPinyin] = v }
    suspend fun setReaderPinyinMode(mode: PinyinMode, top: Int? = null) = context.dataStore.edit {
        it[K.readerPinyinMode] = mode.name
        it[K.readerPinyin] = mode != PinyinMode.OFF
        if (top != null) it[K.readerPinyinTop] = top
    }
    suspend fun setReaderPopup(v: PopupLayout) = context.dataStore.edit { it[K.readerPopup] = v.name }
    suspend fun setNavTabs(v: List<String>) = context.dataStore.edit { it[K.navTabs] = v.distinct().take(MAX_NAV_TABS).joinToString(",") }
    suspend fun setReaderTapLookup(v: Boolean) = context.dataStore.edit { it[K.readerTapLookup] = v }
    suspend fun setReaderShowMyWords(v: Boolean) = context.dataStore.edit { it[K.readerShowMyWords] = v }
    suspend fun setBookText(size: Int? = null, spacing: Float? = null, serif: Boolean? = null) = context.dataStore.edit {
        size?.let { v -> it[K.bookTextSize] = v.coerceIn(13, 32) }
        spacing?.let { v -> it[K.bookLineSpacing] = v.coerceIn(1.2f, 2.2f) }
        serif?.let { v -> it[K.bookSerif] = v }
    }
    suspend fun setReaderSplit(v: Float) = context.dataStore.edit { it[K.readerSplit] = v.coerceIn(0.2f, 0.8f) }
    suspend fun setStoryLevel(level: String, hsk: Int) = context.dataStore.edit { it[K.storyLevel] = level; it[K.storyHsk] = hsk }
    suspend fun setRehearseLevel(v: Int) = context.dataStore.edit { it[K.rehearseLevel] = v.coerceIn(1, 10) }
    suspend fun setRehearseZhLevel(v: Int) = context.dataStore.edit { it[K.rehearseZhLevel] = v.coerceIn(1, 10) }
    suspend fun setRehearseLength(v: Int) = context.dataStore.edit { it[K.rehearseLength] = v.coerceIn(1, 3) }
    suspend fun setRehearseLangs(v: Set<Lang>) = context.dataStore.edit { it[K.rehearseLangs] = v.map { l -> l.code }.toSet() }
    suspend fun setRehearseMyWords(v: Boolean) = context.dataStore.edit { it[K.rehearseMyWords] = v }
    suspend fun setRehearseFrom(v: Lang?) = context.dataStore.edit { if (v == null) it.remove(K.rehearseFrom) else it[K.rehearseFrom] = v.code }
    suspend fun setRehearsePath(v: RehearsePath) = context.dataStore.edit { it[K.rehearsePath] = v.name }
    suspend fun setGrammarFocus(v: String) = context.dataStore.edit { it[K.grammarFocus] = v }
    suspend fun setTranslateEngine(v: TranslateEngine) = context.dataStore.edit { it[K.translateEngine] = v.name }
    suspend fun historySyncedAt(): Long = context.dataStore.data.map { it[K.historySyncedAt] ?: 0L }.first()
    suspend fun setHistorySyncedAt(v: Long) = context.dataStore.edit { it[K.historySyncedAt] = v }
    suspend fun setPinyinHint(v: Boolean) = context.dataStore.edit { it[K.pinyinHint] = v }

    suspend fun resetLayouts() = context.dataStore.edit {
        it.remove(K.dictLayout); it.remove(K.rehearseLayout); it.remove(K.readerLayout); it.remove(K.readerPopup)
    }
}
