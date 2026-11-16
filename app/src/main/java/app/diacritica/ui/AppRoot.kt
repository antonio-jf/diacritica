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
import androidx.compose.material3.Text
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

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", PolyIcons.Home),
    DICTIONARY("dictionary", "Dictionary", PolyIcons.Search),
    READER("reader", "Reader", PolyIcons.Book),
    REHEARSE("rehearse", "Rehearse", PolyIcons.Pencil),
    REVIEW("review", "Review", PolyIcons.Cards),
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
    undo: app.diacritica.undo.UndoManager,
    sharedText: String?,
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
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route
        // A word shared from another app opens straight in the Dictionary (Home is the start page).
        LaunchedEffect(sharedText) { if (!sharedText.isNullOrBlank()) { if (sharedIsPhrase) nav.navigate("translate") else nav.goTo(Tab.DICTIONARY) } }
        Column(Modifier.fillMaxSize().background(Diacritica.colors.ground).statusBarsPadding()) {
            Box(Modifier.weight(1f).imePadding()) {
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
                                    app.diacritica.ui.home.HomeSection.LIBRARY -> nav.navigate("library")
                                    app.diacritica.ui.home.HomeSection.SETTINGS -> nav.navigate("settings")
                                    app.diacritica.ui.home.HomeSection.TRANSLATE -> nav.navigate("translate")
                                    app.diacritica.ui.home.HomeSection.PROGRESS -> nav.navigate("progress")
                                    app.diacritica.ui.home.HomeSection.TESTS -> nav.navigate("tests")
                                    app.diacritica.ui.home.HomeSection.GRAMMAR -> nav.navigate("grammar")
                                }
                            },
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
                        app.diacritica.ui.rehearse.RehearseScreen(
                            rehearseVm, s, onOpenSettings = { nav.navigate("settings") }, onOpenArchive = { nav.navigate("archive") },
                        )
                    }
                    composable(Tab.READER.route) {
                        app.diacritica.ui.reader.ReaderScreen(
                            readerVm, s, onOpenWord = { l, w -> nav.navigate("detail/${l.code}/${Uri.encode(w)}") },
                            onRehearse = { id, idx, l -> rehearseVm.rehearseStorySentence(id, idx, l); nav.goTo(Tab.REHEARSE) },
                            onOpenDataPacks = { nav.navigate("packs") },
                        )
                    }
                    composable("archive") {
                        app.diacritica.ui.rehearse.ArchiveScreen(rehearseVm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("archive/$it") })
                    }
                    composable("archive/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        app.diacritica.ui.rehearse.ArchiveDetailScreen(rehearseVm, entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                    }
                    composable(Tab.REVIEW.route) { app.diacritica.ui.review.ReviewScreen(reviewVm) }
                    composable("library") {
                        LibraryScreen(dictVm, onOpenArchive = { nav.navigate("archive") }, onOpenSettings = { nav.navigate("settings") }) { word, lang ->
                            dictVm.lookUp(word, lang)
                            nav.goTo(Tab.DICTIONARY)
                        }
                    }
                    composable("settings") { SettingsScreen(settingsVm, s, onBack = { nav.popBackStack() }, onOpenAppearance = { nav.navigate("appearance") }, onOpenDataPacks = { nav.navigate("packs") }) }
                    composable("packs") { app.diacritica.ui.packs.DataPacksScreen(packsVm, s, onBack = { nav.popBackStack() }) }
                    composable("tests") {
                        app.diacritica.ui.tests.TestsScreen(
                            testsVm, s, onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate("settings") },
                            onOpenGrammar = { nav.navigate("grammar") },
                        )
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
                }
            }
            val current = Tab.entries.firstOrNull { route == it.route } ?: when {
                route?.startsWith("detail") == true -> Tab.DICTIONARY
                route?.startsWith("archive") == true -> Tab.REHEARSE
                route == "settings" || route == "appearance" || route == "library" || route == "translate" || route == "progress" || route == "packs" || route == "tests" || route == "grammar" -> Tab.HOME
                else -> null
            }
            val offer by undo.offer.collectAsStateWithLifecycle()
            offer?.let { o -> UndoBar(o.message, onUndo = undo::undo, onDismiss = undo::dismiss) }
            BottomNav(current = current) {
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
private fun BottomNav(current: Tab?, onSelect: (Tab) -> Unit) {
    val c = Diacritica.colors
    val tokens = Diacritica.tokens
    Column(Modifier.fillMaxWidth().background(if (tokens.surfaceStyle == SurfaceStyle.TONAL) c.surface else c.ground).navigationBarsPadding()) {
        Hairline(heavy = tokens.heavyRules)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Tab.entries.forEach { t ->
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
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                    Text(
                        t.label,
                        style = TextStyle(
                            fontFamily = tokens.fonts.label, fontSize = 11.sp, color = fg,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
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
