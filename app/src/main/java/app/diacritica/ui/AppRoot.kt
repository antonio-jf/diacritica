package app.diacritica.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.diacritica.data.Lang
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.dictionary.DetailScreen
import app.diacritica.ui.dictionary.DictionaryScreen
import app.diacritica.ui.library.LibraryScreen
import app.diacritica.ui.onboarding.ThemePickerScreen
import app.diacritica.ui.settings.SettingsScreen
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.DiacriticaTheme
import app.diacritica.ui.theme.SurfaceStyle

/** Every section that can sit in the bottom bar (Home always does; the rest are chosen in Settings). */
internal enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", PolyIcons.Home),
    DICTIONARY("dictionary", "Dictionary", PolyIcons.Search),
    READER("reader", "Reader", PolyIcons.Book),
    BOOKS("books", "Books", PolyIcons.Books),
    REHEARSE("rehearse", "Rehearse", PolyIcons.Pencil),
    REVIEW("review", "Review", PolyIcons.Cards),
    TESTS("tests", "Tests", PolyIcons.Quiz),
    GRAMMAR("grammar", "Grammar", PolyIcons.Grammar),
    VOCABULARY("vocabulary", "Vocabulary", PolyIcons.Cards),
    VOICE("voice", "Voice", PolyIcons.Mic),
    TRANSLATE("translate", "Translate", PolyIcons.Translate),
    PROGRESS("progress", "Progress", PolyIcons.Chart),
    LIBRARY("library", "Library", PolyIcons.Library),
    ASK("ask", "Ask", PolyIcons.Chat),
    /** Always the last item of the bar, at the far right; not one of the sections chosen in Settings. */
    SETTINGS("settings", "Settings", PolyIcons.Settings);

    companion object {
        fun byRoute(r: String?): Tab? = entries.firstOrNull { it.route == r }
    }
}

@Composable
fun AppRoot(
    settingsVm: SettingsViewModel,
    dictVm: DictionaryViewModel,
    reviewVm: app.diacritica.ui.review.ReviewViewModel,
    rehearseVm: app.diacritica.ui.rehearse.RehearseViewModel,
    readerVm: app.diacritica.ui.reader.ReaderViewModel,
    homeVm: app.diacritica.ui.home.HomeViewModel,
    translateVm: app.diacritica.ui.translate.TranslateViewModel,
    progressVm: app.diacritica.ui.progress.ProgressViewModel,
    packsVm: app.diacritica.ui.packs.DataPacksViewModel,
    testsVm: app.diacritica.ui.tests.TestsViewModel,
    grammarVm: app.diacritica.ui.grammar.GrammarViewModel,
    booksVm: app.diacritica.ui.books.BooksViewModel,
    vocabVm: app.diacritica.ui.vocab.VocabularyViewModel,
    voiceVm: app.diacritica.voice.VoiceDrillViewModel,
    undo: app.diacritica.undo.UndoManager,
    sharedText: String?,
    /** Text from "Ask Diacritica" in another app's selection menu: opens Ask with it quoted. */
    askText: String? = null,
    onAskOpened: () -> Unit = {},
    openDaily: String? = null,
    onDailyOpened: () -> Unit = {},
) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return // DataStore still loading: keep the window background (no theme flash)

    if (!s.onboarded) {
        ThemePickerScreen(s, onDone = settingsVm::finishOnboarding)
        return
    }

    // Text selected in another app: a word or short phrase goes to the Dictionary, anything longer to Translate.
    val sharedIsPhrase = sharedText?.trim()?.split(Regex("\\s+"))?.let { it.size > 3 } == true || (sharedText?.length ?: 0) > 40
    LaunchedEffect(sharedText) {
        if (sharedText.isNullOrBlank()) return@LaunchedEffect
        if (sharedIsPhrase) translateVm.start(sharedText.trim()) else dictVm.lookUp(sharedText.trim(), null)
    }

    DiacriticaTheme(s) {
        val nav = rememberNavController()
        // Home, then the sections chosen in Settings › Bottom bar.
        // Settings is always last, at the far right.
        val barTabs = listOf(Tab.HOME) + s.navTabs.mapNotNull { Tab.byRoute(it) }.filter { it != Tab.HOME && it != Tab.SETTINGS } + Tab.SETTINGS
        // A section in the bar is opened as a tab; otherwise it's pushed on top (with its own Back).
        fun openSection(t: Tab) { if (t in barTabs) nav.goTo(t) else nav.navigate(t.route) }
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route
        // A word shared from another app opens straight in the Dictionary (Home is the start page).
        LaunchedEffect(sharedText) { if (!sharedText.isNullOrBlank()) { if (sharedIsPhrase) nav.navigate("translate") else nav.goTo(Tab.DICTIONARY) } }
        val chat = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.diacritica.DiacriticaApp).container.chat
        // Selected text → Ask, with the text quoted above the input.
        val ask: (String, String?) -> Unit = androidx.compose.runtime.remember(nav, barTabs) {
            { text: String, source: String? ->
                chat.attach(text, source)
                if (nav.currentDestination?.route != Tab.ASK.route) openSection(Tab.ASK)
            }
        }
        LaunchedEffect(askText) { askText?.takeIf { it.isNotBlank() }?.let { ask(it, "from another app"); onAskOpened() } }
        LaunchedEffect(openDaily) { openDaily?.let { nav.navigate("daily/$it"); onDailyOpened() } }
        Column(Modifier.fillMaxSize().background(Diacritica.colors.ground).statusBarsPadding()) {
            Box(Modifier.weight(1f).imePadding()) {
              androidx.compose.runtime.CompositionLocalProvider(app.diacritica.ui.chat.LocalAsk provides ask) {
                val openWord: (Lang, String) -> Unit = { l, w -> nav.navigate("detail/${l.code}/${Uri.encode(w)}") }
                NavHost(nav, startDestination = Tab.HOME.route) {
                    composable(Tab.HOME.route) {
                        app.diacritica.ui.home.HomeScreen(
                            homeVm, s,
                            onSearch = { q, l -> dictVm.lookUp(q, l); nav.goTo(Tab.DICTIONARY) },
                            onOpenStory = { id -> readerVm.open(id); nav.goTo(Tab.READER) },
                            onOpen = { section ->
                                when (section) {
                                    app.diacritica.ui.home.HomeSection.DICTIONARY -> nav.goTo(Tab.DICTIONARY)
                                    app.diacritica.ui.home.HomeSection.READER -> nav.goTo(Tab.READER)
                                    app.diacritica.ui.home.HomeSection.REHEARSE -> nav.goTo(Tab.REHEARSE)
                                    app.diacritica.ui.home.HomeSection.REVIEW -> nav.goTo(Tab.REVIEW)
                                    app.diacritica.ui.home.HomeSection.LIBRARY, app.diacritica.ui.home.HomeSection.SAVED -> openSection(Tab.LIBRARY)
                                    app.diacritica.ui.home.HomeSection.SETTINGS -> nav.goTo(Tab.SETTINGS)
                                    app.diacritica.ui.home.HomeSection.TRANSLATE -> openSection(Tab.TRANSLATE)
                                    app.diacritica.ui.home.HomeSection.PROGRESS -> openSection(Tab.PROGRESS)
                                    app.diacritica.ui.home.HomeSection.TESTS -> openSection(Tab.TESTS)
                                    app.diacritica.ui.home.HomeSection.GRAMMAR -> openSection(Tab.GRAMMAR)
                                    app.diacritica.ui.home.HomeSection.VOCABULARY -> openSection(Tab.VOCABULARY)
                                    app.diacritica.ui.home.HomeSection.VOICE -> openSection(Tab.VOICE)
                                    app.diacritica.ui.home.HomeSection.BOOKS -> openSection(Tab.BOOKS)
                                    app.diacritica.ui.home.HomeSection.ASK -> openSection(Tab.ASK)
                                }
                            },
                            onChange = settingsVm::setHome,
                            onOpenDaily = { id -> nav.navigate("daily/$id") },
                        )
                    }
                    composable(Tab.DICTIONARY.route) {
                        DictionaryScreen(
                            vm = dictVm,
                            settings = s,
                            onLayoutChange = settingsVm::setDictionaryLayout,
                            onSourceChange = settingsVm::setSourceLang,
                            onOpenDetail = { l, w -> nav.navigate("detail/${l.code}/${Uri.encode(w)}") },
                        )
                    }
                    composable(Tab.REHEARSE.route) {
                        app.diacritica.ui.components.WordTapHost(s.tapLookupPractice, "Rehearse", onOpenDictionary = openWord) {
                            app.diacritica.ui.rehearse.RehearseScreen(
                                rehearseVm, s, onOpenSettings = { nav.goTo(Tab.SETTINGS) }, onOpenArchive = { nav.navigate("archive") },
                                onOpenRehearseSettings = { nav.navigate("rehearse-settings") },
                            )
                        }
                    }
                    composable(Tab.READER.route) {
                        app.diacritica.ui.reader.ReaderScreen(
                            readerVm, s, onOpenWord = { l, w -> nav.navigate("detail/${l.code}/${Uri.encode(w)}") },
                            onRehearse = { id, idx, l -> rehearseVm.rehearseStorySentence(id, idx, l); nav.goTo(Tab.REHEARSE) },
                            onOpenDataPacks = { nav.navigate("packs") },
                        )
                    }
                    composable(Tab.BOOKS.route) {
                        app.diacritica.ui.books.BooksScreen(
                            booksVm,
                            onBack = if (Tab.BOOKS in barTabs) null else ({ nav.popBackStack() }),
                            onOpen = { id -> nav.navigate("book/$id") },
                        )
                    }
                    composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        app.diacritica.ui.books.BookReaderScreen(
                            booksVm, s, entry.arguments?.getLong("id") ?: 0L,
                            onBack = { nav.popBackStack() },
                            onOpenWord = { l, w -> nav.navigate("detail/${l.code}/${Uri.encode(w)}") },
                            onRehearse = { text, l, source, en -> rehearseVm.rehearseText(text, l, source, en); nav.goTo(Tab.REHEARSE) },
                        )
                    }
                    composable("rehearse-settings") {
                        app.diacritica.ui.rehearse.RehearseSettingsScreen(rehearseVm, s, onBack = { nav.popBackStack() }, onOpenSettings = { nav.goTo(Tab.SETTINGS) })
                    }
                    composable("archive") {
                        app.diacritica.ui.rehearse.ArchiveScreen(rehearseVm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("archive/$it") })
                    }
                    composable("archive/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        app.diacritica.ui.rehearse.ArchiveDetailScreen(rehearseVm, entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                    }
                    composable(Tab.REVIEW.route) {
                        app.diacritica.ui.components.WordTapHost(s.tapLookupPractice, "Review", lookup = reviewVm.lookup) {
                            app.diacritica.ui.review.ReviewScreen(reviewVm, onOpenWord = openWord)
                        }
                    }
                    composable("library") {
                        LibraryScreen(dictVm, onOpenArchive = { nav.navigate("archive") }) { word, lang ->
                            dictVm.lookUp(word, lang)
                            nav.goTo(Tab.DICTIONARY)
                        }
                    }
                    composable("settings") { SettingsScreen(settingsVm, s, onBack = null, onOpenAppearance = { nav.navigate("appearance") }, onOpenDataPacks = { nav.navigate("packs") }, onOpenRehearseSettings = { nav.navigate("rehearse-settings") }) }
                    composable("packs") { app.diacritica.ui.packs.DataPacksScreen(packsVm, s, onBack = { nav.popBackStack() }) }
                    composable("tests") {
                        app.diacritica.ui.components.WordTapHost(s.tapLookupPractice, "Tests", onOpenDictionary = openWord) {
                            app.diacritica.ui.tests.TestsScreen(
                                testsVm, s, onBack = { nav.popBackStack() }, onOpenSettings = { nav.goTo(Tab.SETTINGS) },
                                onOpenGrammar = { nav.navigate("grammar") },
                            )
                        }
                    }
                    composable("grammar") {
                        app.diacritica.ui.grammar.GrammarScreen(
                            grammarVm,
                            onBack = { nav.popBackStack() },
                            onStartTest = { test, lang -> testsVm.start(test, Lang.EN, listOf(lang)); nav.navigate("tests") },
                            onPractiseLesson = { lesson, attemptId -> testsVm.practiseFrom(lesson, attemptId); nav.navigate("tests") },
                            onRehearse = { topic -> rehearseVm.focusGrammar(topic); nav.goTo(Tab.REHEARSE) },
                            onOpenTests = { nav.navigate("tests") },
                        )
                    }
                    composable("daily/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        app.diacritica.ui.home.DailyLessonScreen(
                            id, onBack = { nav.popBackStack() },
                            onPractise = { lesson -> testsVm.practiseFrom(lesson, -1L); nav.navigate("tests") },
                        )
                    }
                    composable("voice") {
                        app.diacritica.voice.VoiceDrillScreen(voiceVm, s, onBack = { nav.popBackStack() }, onOpenSettings = { nav.goTo(Tab.SETTINGS) })
                    }
                    composable("vocabulary") {
                        app.diacritica.ui.vocab.VocabularyScreen(
                            vocabVm, onBack = { nav.popBackStack() },
                            onOpenWord = { l, w -> nav.navigate("detail/${l.code}/${Uri.encode(w)}") },
                        )
                    }
                    composable("progress") { app.diacritica.ui.progress.ProgressScreen(progressVm, onBack = { nav.popBackStack() }) }
                    composable("translate") {
                        app.diacritica.ui.translate.TranslateScreen(
                            translateVm, s, onBack = { nav.popBackStack() },
                            onLookUp = { w, l -> dictVm.lookUp(w, l); nav.goTo(Tab.DICTIONARY) },
                        )
                    }
                    composable("appearance") { app.diacritica.ui.settings.AppearanceScreen(settingsVm, s, onBack = { nav.popBackStack() }) }
                    composable(
                        "detail/{lang}/{word}",
                        arguments = listOf(navArgument("lang") { type = NavType.StringType }, navArgument("word") { type = NavType.StringType }),
                    ) { entry ->
                        val lang = Lang.fromCode(entry.arguments?.getString("lang") ?: "") ?: Lang.EN
                        val word = entry.arguments?.getString("word") ?: ""
                        DetailScreen(
                            vm = dictVm, lang = lang, word = word,
                            onBack = { nav.popBackStack() },
                            onPivot = { l, w -> dictVm.lookUp(w, l); nav.goTo(Tab.DICTIONARY) },
                        )
                    }
                    composable(Tab.ASK.route) {
                        app.diacritica.ui.chat.ChatScreen(
                            s, onBack = if (Tab.ASK in barTabs) null else ({ nav.popBackStack() }),
                            onOpenSettings = { nav.goTo(Tab.SETTINGS) },
                        )
                    }
                }
              }
            }
            val bar = barTabs
            val current = Tab.byRoute(route)?.takeIf { it in bar } ?: when {
                route?.startsWith("detail") == true -> Tab.DICTIONARY.takeIf { it in bar } ?: Tab.HOME
                route?.startsWith("archive") == true || route == "rehearse-settings" -> Tab.REHEARSE.takeIf { it in bar } ?: Tab.HOME
                route?.startsWith("book/") == true -> Tab.BOOKS.takeIf { it in bar } ?: Tab.HOME
                route == "appearance" || route == "packs" -> Tab.SETTINGS
                route != null -> Tab.HOME
                else -> null
            }
            val offer by undo.offer.collectAsStateWithLifecycle()
            offer?.let { o -> UndoBar(o.message, onUndo = undo::undo, onDismiss = undo::dismiss) }
            // Reading a book: the whole screen is the page.
            if (route?.startsWith("book/") != true) BottomNav(items = bar, current = current) {
                nav.goTo(it)
            }
        }
    }
}

private fun NavHostController.goTo(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = tab != Tab.DICTIONARY && tab != Tab.HOME
    }
}

@Composable
private fun BottomNav(items: List<Tab>, current: Tab?, onSelect: (Tab) -> Unit) {
    val c = Diacritica.colors
    val tokens = Diacritica.tokens
    Column(Modifier.fillMaxWidth().background(if (tokens.surfaceStyle == SurfaceStyle.TONAL) c.surface else c.ground).navigationBarsPadding()) {
        Hairline(heavy = tokens.heavyRules)
        // Equal widths, so up to six items (Home, four sections, Settings) fit on a phone.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val navScale = (if (fontScale > 1.3f) 1.3f / fontScale else 1f) * (if (items.size >= 6) 0.94f else 1f)
            items.forEach { t ->
                val selected = t == current
                val pill = selected && (tokens.surfaceStyle == SurfaceStyle.TONAL || tokens.surfaceStyle == SurfaceStyle.RULED)
                val fg = when {
                    pill && tokens.surfaceStyle == SurfaceStyle.RULED -> c.ground
                    pill -> c.onAccentSoft
                    selected -> if (tokens.surfaceStyle == SurfaceStyle.BORDERED) c.accent else c.ink
                    else -> c.inkMuted
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(tokens.shapes.button.coerceAtMost(20.dp)))
                        .background(
                            when {
                                pill && tokens.surfaceStyle == SurfaceStyle.RULED -> c.ink
                                pill -> c.accentSoft
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            },
                        )
                        .clickable(role = Role.Tab) { onSelect(t) }
                        .semantics { this.selected = selected }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                    Text(
                        t.label,
                        style = TextStyle(
                            // Tab labels grow with the system font size only up to 1.3x, so five tabs still fit.
                            fontFamily = tokens.fonts.label, fontSize = (11f * navScale).sp, color = fg,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (selected && tokens.surfaceStyle == SurfaceStyle.HAIRLINE) {
                        Box(Modifier.padding(top = 3.dp).width(18.dp).height(2.dp).background(c.accent))
                    }
                }
            }
        }
    }
}

/** "Removed … · Undo", above the tab bar for a few seconds after a deletion. */
@Composable
private fun UndoBar(message: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val c = Diacritica.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(c.ink)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = app.diacritica.ui.components.bodyStyle(c.ground, 14.sp), maxLines = 2, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        androidx.compose.material3.TextButton(onClick = onUndo) {
            Text("Undo", style = app.diacritica.ui.components.bodyStyle(c.accentSoft, 14.sp).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        }
        app.diacritica.ui.components.PolyIconButton(app.diacritica.ui.components.PolyIcons.Close, "Dismiss", onDismiss, tint = c.ground.copy(alpha = 0.7f))
    }
}
