@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.dictionary

import app.diacritica.ui.theme.hanTransformation
import androidx.compose.foundation.layout.FlowRow
import app.diacritica.ui.components.MenuSection
import app.diacritica.ui.components.ChoiceMenuItem
import app.diacritica.data.user.HistoryItem
import app.diacritica.ui.components.SwipeToDelete
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.settings.AppSettings
import app.diacritica.settings.DictionaryLayout
import app.diacritica.ui.DictionaryViewModel
import app.diacritica.ui.OnlineStatus
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.components.FalseFriendNote
import app.diacritica.ui.components.FormNotes
import app.diacritica.ui.components.HandwritingPad
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.SurfaceStyle

@Composable
fun DictionaryScreen(
    vm: DictionaryViewModel,
    settings: AppSettings,
    onLayoutChange: (DictionaryLayout) -> Unit,
    onSourceChange: (Lang?) -> Unit,
    onOpenDetail: (Lang, String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val missingVoice by vm.missingVoice.collectAsStateWithLifecycle()
    val pending by vm.pendingWords.collectAsStateWithLifecycle()
    val downloading by vm.downloadingPending.collectAsStateWithLifecycle()
    val online by app.diacritica.ui.components.rememberIsOnline()
    // Back online: download the searches that were queued while offline.
    androidx.compose.runtime.LaunchedEffect(online, pending.isNotEmpty()) { if (online && pending.isNotEmpty()) vm.downloadPending() }
    val focus = LocalFocusManager.current
    var pad by rememberSaveable { mutableStateOf(false) }
    // The search box rests lower on the screen while there's nothing to show, and rises to the top
    // as soon as you tap it (or there's a word, or the handwriting pad is open).
    var focused by remember { mutableStateOf(false) }
    val raised = focused || state.query.isNotBlank() || pad

    val actions = WordActions(
        onSpeak = { w, slow -> vm.speak(w, slow) },
        onToggleSave = vm::toggleSave,
        onOpen = { w -> onOpenDetail(w.lang, w.word) },
        isSaved = { w -> "${w.lang.code}:${w.word}" in savedKeys },
        onSuggest = vm::suggest,
        suggestion = { l -> state.suggestions[state.result?.concepts?.getOrNull(state.result?.selected ?: 0)?.let { "${it.id}:${l.code}" } ?: ""] },
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item("header") {
            ScreenHeader("Dictionary") {
                var menu by remember { mutableStateOf(false) }
                Box {
                    PolyIconButton(PolyIcons.Layout, "Results layout: ${settings.dictionaryLayout.label}", { menu = true }, tint = Diacritica.colors.inkMuted)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Diacritica.colors.ground) {
                        MenuSection("Results layout")
                        DictionaryLayout.entries.forEach { l ->
                            ChoiceMenuItem(l.label, l == settings.dictionaryLayout, { menu = false; onLayoutChange(l) })
                        }
                    }
                }
            }
        }
        item("hero") { SearchHero(raised, settings.activeLanguages) }
        item("search") {
            SearchField(
                onFocus = { focused = it },
                query = state.query,
                source = settings.sourceLang,
                active = settings.activeLanguages,
                onQueryChange = vm::onQueryChange,
                onSubmit = { vm.submit(); focus.clearFocus() },
                onClear = vm::clear,
                onSourceChange = onSourceChange,
                onHandwriting = if (Lang.ZH in settings.activeLanguages) ({ pad = !pad }) else null,
            )
        }
        if (pad) item("pad") {
            HandwritingPad(
                before = state.query,
                onInsert = { vm.onQueryChange(state.query + it) },
                onBackspace = { vm.onQueryChange(state.query.dropLast(1)) },
                onClose = { pad = false },
                modifier = Modifier.padding(horizontal = Diacritica.spacing.screen).padding(bottom = 8.dp),
            )
        }
        if (missingVoice != null) item("voice") {
            VoiceMissingBanner(missingVoice!!, onInstall = { vm.openVoiceSettings(); vm.clearMissingVoice() }, onDismiss = vm::clearMissingVoice)
        }
        if (pending.isNotEmpty() && state.query.isBlank()) item("pending") {
            val n = pending.size
            val words = if (n == 1) "1 search" else "$n searches"
            app.diacritica.ui.components.OfflineNotice(
                when {
                    !online -> "Offline: $words will be downloaded when you're back online."
                    downloading -> "Downloading $words you made offline…"
                    else -> "$words made offline still to download."
                },
                Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 4.dp),
                onRetry = if (online && !downloading) vm::downloadPending else null,
            )
        }
        val r = state.result
        state.online?.let { st -> if (state.query.isNotBlank()) item("online") { OnlineBanner(st) } }
        when {
            state.query.isBlank() -> recentItems(history.take(12), onDelete = vm::deleteHistory) { q, l -> vm.lookUp(q, l) }
            r == null -> {}
            r.isEmpty -> if (state.online != OnlineStatus.FETCHING) item("empty") { EmptyResult(r) { vm.lookUp(it, r.source) } }
            else -> {
                item("head") { Headword(r, actions, onPickLang = { onSourceChange(it); vm.lookUp(r.query, it) }, onLookUp = { w, l -> vm.lookUp(w, l) }) }
                if (r.zhAlternatives.isNotEmpty()) item("zh-alt") { ZhAlternatives(r) { vm.lookUp(it, Lang.ZH) } }
                if (r.concepts.size > 1) item("senses") { SensePicker(r) { vm.selectSense(it) } }
                val warnings = vm.falseFriends(r, settings.activeLanguages)
                if (warnings.isNotEmpty()) item("ff") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        warnings.forEach { FalseFriendNote(it) }
                    }
                }
                item("gap") { Gap(4.dp) }
                dictionaryResults(settings.dictionaryLayout, r.results, actions)
                item("foot") { Gap(24.dp) }
            }
        }
    }
}

// ---------------------------------------------------------------- search field

@Composable
private fun SearchField(
    query: String,
    source: Lang?,
    active: List<Lang>,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onSourceChange: (Lang?) -> Unit,
    onHandwriting: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    val c = Diacritica.colors
    val shapes = Diacritica.shapes
    val style = Diacritica.tokens.surfaceStyle
    val focusRequester = remember { FocusRequester() }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                onQueryChange(it); onSubmit()
            }
        }
    }
    val fieldShape = RoundedCornerShape(shapes.field)
    val container = when (style) {
        SurfaceStyle.HAIRLINE -> Modifier
        SurfaceStyle.TONAL -> Modifier.clip(fieldShape).background(c.surface)
        SurfaceStyle.BORDERED -> Modifier.clip(fieldShape).background(c.surface).border(BorderStroke(1.dp, c.line), fieldShape)
        SurfaceStyle.RULED -> Modifier.border(BorderStroke(2.dp, c.rule))
    }
    Column(Modifier.padding(horizontal = Diacritica.spacing.screen).padding(top = 4.dp, bottom = 10.dp)) {
        Row(
            container.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = if (style == SurfaceStyle.HAIRLINE) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SourceSelector(source, active, onSourceChange)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Type a word…", style = TextStyle(fontFamily = Diacritica.fonts.display, fontSize = 22.sp, color = c.inkMuted))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Diacritica.fonts.display, fontSize = 22.sp, color = c.ink, fontWeight = FontWeight(Diacritica.type.headwordWeight)),
                    visualTransformation = hanTransformation(),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = app.diacritica.ui.components.keyboardFor(source, KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false)),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        // Tapping in to type a new word goes back to auto-detect; pick a language again from the chip if needed.
                        .onFocusChanged { onFocus(it.isFocused); if (it.isFocused && source != null) onSourceChange(null) }
                        .semantics { contentDescription = "Search word" },
                )
            }
            if (query.isNotEmpty()) PolyIconButton(PolyIcons.Close, "Clear", onClear, tint = c.inkMuted)
            onHandwriting?.let { PolyIconButton(PolyIcons.Handwriting, "Write Chinese characters by hand", it, tint = c.inkMuted) }
            PolyIconButton(PolyIcons.Mic, "Voice input", {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, (source ?: Lang.EN).locale.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                runCatching { speech.launch(intent) }
            }, tint = c.inkMuted)
        }
        if (style == SurfaceStyle.HAIRLINE) Hairline(Modifier.padding(top = 0.dp), heavy = false)
    }
}

/**
 * The space above the search box: about a fifth of the screen with a short prompt while the Dictionary is
 * idle, shrinking to nothing (so the box glides to the top) once you start searching.
 */
@Composable
private fun SearchHero(raised: Boolean, active: List<Lang>) {
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val height by androidx.compose.animation.core.animateDpAsState(
        if (raised) 0.dp else screen * 0.2f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "searchHero",
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (raised) 0f else 1f, label = "searchHeroAlpha")
    Box(
        Modifier.fillMaxWidth().height(height).clipToBounds().graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.padding(horizontal = Diacritica.spacing.screen).padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Look up a word", style = headwordStyle(28.sp).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            Text(
                "in any of your languages · " + active.joinToString(" ") { it.short },
                style = captionStyle().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
}

@Composable
private fun SourceSelector(source: Lang?, active: List<Lang>, onChange: (Lang?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = Diacritica.colors
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(Diacritica.shapes.chip)).background(c.accentSoft)
                .clickable(role = Role.Button) { open = true }
                .heightIn(min = 40.dp).padding(horizontal = 10.dp).semantics { contentDescription = "Source language: ${source?.englishName ?: "auto-detect"}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (source?.short ?: "Auto") + " ▾",
                style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onAccentSoft),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto-detect") }, onClick = { onChange(null); open = false })
            active.forEach { l ->
                DropdownMenuItem(text = { Text("${l.short}  ·  ${l.nativeName}") }, onClick = { onChange(l); open = false })
            }
        }
    }
}

// ---------------------------------------------------------------- result pieces

@Composable
private fun Headword(r: LookupResult, actions: WordActions, onPickLang: (Lang) -> Unit, onLookUp: (String, Lang) -> Unit) {
    val h = r.headword ?: return
    Column(Modifier.padding(horizontal = Diacritica.spacing.screen).padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(role = Role.Button) { actions.onOpen(h) }) {
                SectionLabel("${r.source.englishName}${h.pos?.let { " · $it" } ?: ""}")
                WordTitle(h, large = true)
                if (h.lang == Lang.ZH) PinyinLine(h)
                grammarLine(h)?.let { Text(it, style = captionStyle()) }
            }
            WordActionButtons(h, actions)
        }
        app.diacritica.ui.components.VerbPatternNotes(h)
        if (h.lang == Lang.ZH) app.diacritica.ui.components.HanziBreakdown(h.word, h.pinyin, Modifier.padding(top = 4.dp), onOpen = { ch -> onLookUp(ch, Lang.ZH) })
        if (r.forms.isNotEmpty()) {
            // Looking up the base word from here replaces the search, like typing it.
            FormNotes(r.query, r.forms, onLemma = { l -> onLookUp(l, r.source) })
        } else {
            r.redirectedFrom?.let { Text("“$it” is a form of “${h.word}”.", style = captionStyle(Diacritica.colors.warning)) }
        }
        if (r.alsoFoundIn.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Also a word in", style = captionStyle())
                r.alsoFoundIn.forEach { l -> PolyChip(l.short, selected = false, onClick = { onPickLang(l) }) }
            }
        }
    }
}

/** Homophones / other matches for a pinyin search: "老实 lǎoshi · honest". */
@Composable
private fun ZhAlternatives(r: LookupResult, onPick: (String) -> Unit) {
    Column(Modifier.padding(bottom = 6.dp)) {
        SectionLabel("Other matches", Modifier.padding(horizontal = Diacritica.spacing.screen))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            r.zhAlternatives.forEach { z ->
                val gloss = z.definitions.firstOrNull()?.substringBefore(';')?.take(18).orEmpty()
                PolyChip("${z.simplified} ${z.pinyin}" + if (gloss.isNotEmpty()) " · $gloss" else "", selected = false, onClick = { onPick(z.simplified) })
            }
        }
    }
}

@Composable
private fun SensePicker(r: LookupResult, onSelect: (Int) -> Unit) {
    Column {
        SectionLabel("Meaning", Modifier.padding(horizontal = Diacritica.spacing.screen))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            r.concepts.forEachIndexed { i, c ->
                val label = c.label.ifBlank { c.enWord }
                PolyChip(
                    "${i + 1} · ${if (c.pos != null && c.pos != "noun") "(${c.pos}) " else ""}$label",
                    selected = i == r.selected,
                    onClick = { onSelect(i) },
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyResult(r: LookupResult, onSuggestion: (String) -> Unit) {
    Column(Modifier.padding(Diacritica.spacing.screen), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nothing found for “${r.query}” in ${r.source.englishName}.", style = bodyStyle())
        if (r.suggestions.isNotEmpty()) {
            SectionLabel("Did you mean")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.suggestions.forEach { s -> PolyChip(s, selected = false, onClick = { onSuggestion(s) }) }
            }
        }
        Text(
            "Tip: set the source language to Auto, or turn on “Download missing words” in Settings.",
            style = captionStyle(),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recentItems(
    recent: List<HistoryItem>,
    onDelete: (HistoryItem) -> Unit,
    onPick: (String, Lang?) -> Unit,
) {
    if (recent.isEmpty()) {
        item("hint") {
            Text(
                "Type a word in any of your languages. Results appear in all of them at once.",
                style = captionStyle(), modifier = Modifier.padding(Diacritica.spacing.screen),
            )
        }
        return
    }
    item("recent-label") { SectionLabel("Recent", Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
    items(recent, key = { "r-${it.id}" }) { h ->
        val q = h.query
        val l = Lang.fromCode(h.lang)
        SwipeToDelete(onDelete = { onDelete(h) }) {
        Column(Modifier.background(Diacritica.colors.ground).padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(q, l) }.padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(l?.short ?: "", Modifier.widthIn(min = 30.dp))
                Text(q, style = bodyStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Hairline()
        }
        }
    }
}

@Composable
private fun OnlineBanner(status: OnlineStatus) {
    val c = Diacritica.colors
    val text = when (status) {
        OnlineStatus.FETCHING -> "Not in the offline dictionary yet. Looking it up online…"
        OnlineStatus.DOWNLOADED -> "Downloaded from Wiktionary and saved. It now works offline too."
        OnlineStatus.NOT_FOUND -> "Not found online either. Check the spelling or the source language."
        OnlineStatus.OFFLINE_QUEUED -> "You're offline. This word will be downloaded the next time you're online."
        OnlineStatus.FAILED -> "Couldn't reach the online dictionary right now. Try again later."
    }
    val color = when (status) {
        OnlineStatus.DOWNLOADED -> c.success
        OnlineStatus.NOT_FOUND, OnlineStatus.FAILED -> c.warning
        else -> c.inkMuted
    }
    Text(text, style = captionStyle(color), modifier = Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp))
}

@Composable
private fun VoiceMissingBanner(lang: Lang, onInstall: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.card))
            .background(Diacritica.colors.accentSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No ${lang.englishName} voice is installed for offline pronunciation.", style = bodyStyle(Diacritica.colors.onAccentSoft))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PolyButton("Install voice", onInstall)
            PolyButton("Dismiss", onDismiss, primary = false)
        }
    }
}
