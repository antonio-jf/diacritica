package app.diacritica.ui.reader

import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.drawBehind
import app.diacritica.settings.PinyinMode
import app.diacritica.settings.PopupLayout
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import app.diacritica.data.ZhRanking
import app.diacritica.data.ScriptPreference
import app.diacritica.data.WordInfo
import app.diacritica.reader.LoadedStory
import app.diacritica.reader.OfficialDictionary
import app.diacritica.settings.AppSettings
import app.diacritica.settings.ReaderLayout
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.components.FalseFriendNote
import app.diacritica.ui.components.FormNotes
import app.diacritica.ui.theme.Diacritica

/** Entry point of the Reader tab: the story shelf, or the open story. */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    settings: AppSettings,
    onOpenWord: (Lang, String) -> Unit,
    onRehearse: (storyId: Long, idx: Int, lang: Lang) -> Unit = { _, _, _ -> },
    onOpenDataPacks: () -> Unit = {},
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val story = st.story
    if (story == null) {
        Gallery(vm, st, settings, onOpenDataPacks)
    } else {
        BackHandler { vm.close() }
        StoryView(vm, st, story, settings, onOpenWord, onRehearse)
    }
}

// ================================================================= shelf

private val TOPICS = listOf("Daily life", "Travel", "Food", "Work", "Mystery", "Nature", "Family", "History", "City", "Friendship")
private val LEVELS = listOf("A2", "B1", "B2", "C1")
private val HSK = listOf(2, 3, 4, 5)

/** One-tap story ideas for the New story tab: title, what Gemini should write about, and a suggested level. */
private data class StoryIdea(val title: String, val topic: String, val level: String)

private val IDEAS = listOf(
    StoryIdea("The wrong train", "taking the wrong train and making an unexpected friend", "A2"),
    StoryIdea("Market day", "shopping at a busy outdoor market and asking about prices", "A2"),
    StoryIdea("A new neighbour", "a new neighbour moves in and needs help settling in", "A2"),
    StoryIdea("The missing painting", "a small mystery: a painting disappears from a village museum", "B1"),
    StoryIdea("The job interview", "a nervous job interview that goes surprisingly well", "B1"),
    StoryIdea("Lost in the old town", "getting lost in an old city at night and asking for directions", "B1"),
    StoryIdea("The lighthouse keeper", "the last night of a lighthouse keeper before the light becomes automatic", "B2"),
    StoryIdea("A letter from 1962", "finding an old letter in a second-hand book and tracing who wrote it", "B2"),
    StoryIdea("The chess match", "a chess match between a grandmother and a teenage champion", "C1"),
)

/** The Reader's start page: your shelf, the Classics and new stories. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gallery(vm: ReaderViewModel, st: ReaderUiState, settings: AppSettings, onOpenDataPacks: () -> Unit) {
    val c = Diacritica.colors
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val classics by vm.classics.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var topic by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<ShelfItem?>(null) }
    val a = settings.readerLangA
    val b = settings.readerLangB
    val pad = Diacritica.spacing.screen
    LaunchedEffect(a, b) { vm.loadClassics() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Reader") }
        item {
            Column(Modifier.padding(horizontal = pad)) {
                LangPairRow(a, b, Lang.entries, onChange = vm::setLangs)
                Gap(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PolyChip("My shelf (${shelf.size})", tab == 0, { tab = 0 })
                    PolyChip("Classics", tab == 1, { tab = 1 })
                    PolyChip("New story", tab == 2, { tab = 2 })
                }
                Gap(12.dp)
            }
        }

        when (tab) {
            // ------------------------------------------------ My shelf
            0 -> {
                if (shelf.isEmpty()) item {
                    Text("Nothing here yet. Open a Classic or write a new story.", style = captionStyle(), modifier = Modifier.padding(horizontal = pad))
                }
                items(shelf, key = { it.story.id }) { item ->
                    val s = item.story
                    val read = if (s.sentenceCount == 0) 0 else ((s.lastSentence + 1) * 100 / s.sentenceCount).coerceIn(0, 100)
                    val started = s.lastOpenedAt != null
                    val isBook = s.source.startsWith("book:")
                    Box(Modifier.padding(horizontal = pad, vertical = 5.dp)) {
                        PolySurface(onClick = { vm.open(s.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.titles[a] ?: item.titles[Lang.EN] ?: item.titles.values.firstOrNull() ?: "Story",
                                        style = headwordStyle(19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!isBook) item.titles[b]?.let {
                                        Text(it, style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Gap(6.dp)
                                    val meta = buildList {
                                        if (isBook) s.topic?.let { add(it) } else add(s.level)
                                        add("${s.sentenceCount} sentences")
                                        if (!isBook) s.topic?.let { add(it) }
                                        add(if (started) "$read% read" else "new")
                                    }.joinToString(" · ")
                                    Text(meta, style = captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (started) { Gap(6.dp); ProgressBar(read / 100f) }
                                }
                                PolyIconButton(PolyIcons.Trash, "Remove from shelf", onClick = { confirmDelete = item }, tint = c.inkMuted)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ Classics
            1 -> {
                if (!classics.available) item {
                    Box(Modifier.padding(horizontal = pad)) {
                        PolySurface {
                            Text("Classics in two languages", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                            Text("Public-domain novels and stories (Austen, Verne, Dumas, Kafka, Galdós…) lined up sentence by sentence. " +
                                "They come as a data pack.", style = captionStyle())
                            Gap(10.dp)
                            PolyButton("Get the Classics pack", onOpenDataPacks)
                        }
                    }
                } else {
                    item {
                        Column(Modifier.padding(horizontal = pad)) {
                            TopicField(classics.query, vm::setClassicsQuery, placeholder = "Search title or author")
                            Gap(8.dp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PolyChip("${a.short} + ${b.short}", classics.onlyPair, { vm.setOnlyPair(true) })
                                Spacer(Modifier.width(8.dp))
                                PolyChip("All pairs", !classics.onlyPair, { vm.setOnlyPair(false) })
                            }
                            Gap(8.dp)
                            if (!classics.loading && classics.books.isEmpty()) {
                                Text(
                                    if (classics.onlyPair) "No books for ${a.englishName} + ${b.englishName}. Try All pairs, or another language pair (Mandarin isn't in this collection)."
                                    else "No books match.",
                                    style = captionStyle(),
                                )
                            }
                        }
                    }
                    items(classics.books, key = { "book-${it.id}" }) { bk ->
                        Box(Modifier.padding(horizontal = pad, vertical = 4.dp)) {
                            PolySurface(onClick = { vm.openBook(bk) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(bk.title, style = headwordStyle(18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(bk.author, style = bodyStyle(c.inkMuted, 14.sp), maxLines = 1)
                                        Gap(4.dp)
                                        Text("${bk.sentences} sentences · about ${formatMinutes(bk.minutes)}", style = captionStyle())
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        LangBadge(bk.a); LangBadge(bk.b)
                                    }
                                }
                                if (classics.opening == bk.id) { Gap(6.dp); Text("Putting it on your shelf…", style = captionStyle(c.accent)) }
                            }
                        }
                    }
                    item {
                        Text("Texts: OPUS Books (public domain; aligned by András Farkas). Older translations can be loose.",
                            style = captionStyle(), modifier = Modifier.padding(horizontal = pad, vertical = 10.dp))
                    }
                }
            }

            // ------------------------------------------------ New story
            else -> {
                item {
                    Column(Modifier.padding(horizontal = pad)) {
                        Text("Written by Gemini in all six languages, aligned sentence by sentence. Needs your key and the internet; saved stories work offline.",
                            style = captionStyle())
                        Gap(10.dp)
                        SectionLabel("Ideas")
                    }
                }
                items(IDEAS, key = { it.title }) { idea ->
                    Box(Modifier.padding(horizontal = pad, vertical = 4.dp)) {
                        PolySurface(onClick = { if (!st.generating) vm.generate(idea.topic, idea.level) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(idea.title, style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                                    Text(idea.topic.replaceFirstChar { it.uppercase() }, style = captionStyle(), maxLines = 2)
                                }
                                Text(idea.level, style = captionStyle(c.accent))
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = pad).padding(top = 14.dp)) {
                        SectionLabel("Your own")
                        Gap(6.dp)
                        TopicField(topic, { topic = it })
                        Gap(8.dp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TOPICS.forEach { t -> PolyChip(t, selected = topic == t, onClick = { topic = if (topic == t) "" else t }) }
                        }
                        Gap(12.dp)
                        Text("Level", style = captionStyle(c.ink))
                        Gap(6.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LEVELS.forEach { l -> PolyChip(l, selected = settings.storyLevel == l, onClick = { vm.setStoryLevel(l, settings.storyHsk) }) }
                        }
                        Gap(6.dp)
                        Text("Mandarin (HSK)", style = captionStyle(c.ink))
                        Gap(6.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HSK.forEach { h -> PolyChip("HSK $h", selected = settings.storyHsk == h, onClick = { vm.setStoryLevel(settings.storyLevel, h) }) }
                        }
                        Gap(14.dp)
                        PolyButton(
                            if (st.generating) "Writing the story…" else "Write this story",
                            onClick = { vm.generate(topic) }, enabled = !st.generating, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = pad)) {
                if (st.generating) { Gap(8.dp); Text("Writing the story… this takes about 20–40 seconds.", style = captionStyle(c.accent)) }
                st.error?.let { Gap(8.dp); Text(it, style = captionStyle(c.error)) }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove from your shelf?", style = titleStyle().copy(fontSize = 20.sp)) },
            text = {
                Text((item.titles[a] ?: item.titles[Lang.EN] ?: item.titles.values.firstOrNull() ?: "") +
                    if (item.story.source.startsWith("book:")) "\n\nYou can add it again from Classics; your progress will start over." else "",
                    style = bodyStyle())
            },
            confirmButton = { TextButton(onClick = { vm.delete(item.story.id); confirmDelete = null }) { Text("Remove", color = c.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = c.ink) } },
            containerColor = c.ground,
        )
    }
}

private fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    else -> "${m / 60} h" + if (m % 60 >= 10) " ${m % 60} min" else ""
}

@Composable
private fun TopicField(value: String, onChange: (String) -> Unit, placeholder: String = "Topic: anything you like, or pick one below") {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(BorderStroke(if (Diacritica.tokens.heavyRules) 2.dp else 1.dp, if (Diacritica.tokens.heavyRules) c.rule else c.line), shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = bodyStyle(c.inkMuted))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = bodyStyle(), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
    }
}

/** Two language pickers with a swap button. */
@Composable
private fun LangPairRow(a: Lang, b: Lang, options: List<Lang>, onChange: (Lang, Lang) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LangPicker(a, options) { l -> onChange(l, if (l == b) a else b) }
        PolyIconButton(PolyIcons.Swap, "Swap languages", onClick = { onChange(b, a) })
        LangPicker(b, options) { l -> onChange(if (l == a) b else a, l) }
    }
}

@Composable
private fun LangPicker(value: Lang, options: List<Lang>, onPick: (Lang) -> Unit) {
    val c = Diacritica.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Diacritica.shapes.chip)
    Box {
        Row(
            Modifier.clip(shape).border(1.dp, c.line, shape).clickable { open = true }.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LangBadge(value)
            Spacer(Modifier.width(6.dp))
            Text(value.nativeName, style = bodyStyle(size = 14.sp))
            Icon(PolyIcons.ChevronDown, null, tint = c.inkMuted, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            options.forEach { l ->
                DropdownMenuItem(
                    text = { Text("${l.nativeName}  ·  ${l.englishName}", style = bodyStyle(if (l == value) c.accent else c.ink)) },
                    onClick = { open = false; onPick(l) },
                )
            }
        }
    }
}

// ================================================================= story view

/** Everything a sentence needs to render itself and react to touches. */
private class SentenceEnv(
    val vm: ReaderViewModel,
    val story: LoadedStory,
    val highlighted: Int?,
    val popup: WordPopup?,
    val pinyinMode: PinyinMode,
    /** In RARE mode: characters outside this many most common ones get pinyin. */
    val pinyinTop: Int,
    val traditional: Boolean,
    val a: Lang,
    val b: Lang,
    /** Top/bottom layouts: the top pane's share of the height. */
    val split: Float = 0.5f,
    /** A tap looks up the word; long-press highlights the sentence. */
    val tapLookup: Boolean = false,
    /** The second language is hidden until revealed sentence by sentence. */
    val hideOther: Boolean = false,
    val revealed: Set<Int> = emptySet(),
) {
    fun other(l: Lang) = if (l == a) b else a

    /** The pinyin to show above [t], or null (common word in RARE mode, or pinyin off). */
    fun pinyinFor(t: app.diacritica.data.DictionaryRepository.ZhToken): String? = when (pinyinMode) {
        PinyinMode.OFF -> null
        PinyinMode.ALL -> t.pinyin
        PinyinMode.RARE -> t.pinyin?.takeIf { vm.isRare(t.text, pinyinTop) }
    }
    fun text(idx: Int, l: Lang): String = story.sentences.getOrNull(idx)?.get(l).orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryView(
    vm: ReaderViewModel, st: ReaderUiState, story: LoadedStory, settings: AppSettings,
    onOpenWord: (Lang, String) -> Unit, onRehearse: (Long, Int, Lang) -> Unit,
) {
    val c = Diacritica.colors
    val langs = story.languages
    // Fall back to the story's own languages when the chosen pair isn't in it.
    val a = settings.readerLangA.takeIf { it in langs } ?: langs.firstOrNull() ?: Lang.EN
    val b = settings.readerLangB.takeIf { it in langs && it != a } ?: langs.firstOrNull { it != a } ?: a
    val traditional = settings.script == ScriptPreference.TRADITIONAL_FIRST || settings.script == ScriptPreference.TRADITIONAL_ONLY
    val env = SentenceEnv(
        vm, story, st.highlighted, st.popup, settings.readerPinyinMode, settings.readerPinyinTop, traditional, a, b, settings.readerSplit,
        tapLookup = settings.readerTapLookup, hideOther = settings.readerHideOther, revealed = st.revealed,
    )
    val reading by vm.reading.collectAsStateWithLifecycle()
    var layoutMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back to stories", onClick = vm::close, tint = c.ink)
            Column(Modifier.weight(1f)) {
                Text(story.title(a), style = headwordStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(story.title(b), style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ReadAloudButton(playing = reading?.storyId == story.story.id, a = a, b = b, onPlay = vm::readAloud, onStop = vm::stopReading)
            if (Lang.ZH == a || Lang.ZH == b) {
                PinyinMenu(settings.readerPinyinMode, settings.readerPinyinTop, vm::setPinyinMode)
                Spacer(Modifier.width(4.dp))
            }
            Box {
                PolyIconButton(PolyIcons.Layout, "Reader layout", onClick = { layoutMenu = true })
                DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }, containerColor = c.ground) {
                    ReaderLayout.entries.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(l.label, style = bodyStyle(if (l == settings.readerLayout) c.accent else c.ink)) },
                            onClick = { layoutMenu = false; vm.setLayout(l) },
                        )
                    }
                    Text("Reading", style = captionStyle(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                    DropdownMenuItem(
                        text = { Text((if (settings.readerTapLookup) "✓ " else "") + "Tap a word to look it up", style = bodyStyle(if (settings.readerTapLookup) c.accent else c.ink)) },
                        onClick = { layoutMenu = false; vm.setTapLookup(!settings.readerTapLookup) },
                    )
                    DropdownMenuItem(
                        text = { Text((if (settings.readerHideOther) "✓ " else "") + "Hide the translation until tapped", style = bodyStyle(if (settings.readerHideOther) c.accent else c.ink)) },
                        onClick = { layoutMenu = false; vm.setHideOther(!settings.readerHideOther) },
                    )
                    Text("Word popup", style = captionStyle(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                    PopupLayout.entries.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(l.label, style = bodyStyle(if (l == settings.readerPopup) c.accent else c.ink)) },
                            onClick = { layoutMenu = false; vm.setPopupLayout(l) },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Diacritica.spacing.screen).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LangPairRow(a, b, langs, onChange = vm::setLangs)
        }
        val n = story.sentences.size
        val pos = (st.highlighted ?: story.story.lastSentence).coerceIn(0, (n - 1).coerceAtLeast(0))
        ProgressBar(if (n == 0) 0f else (pos + 1f) / n, Modifier.padding(horizontal = Diacritica.spacing.screen))
        Gap(4.dp)
        Hairline(heavy = Diacritica.tokens.heavyRules)

        Box(Modifier.weight(1f)) {
            when (settings.readerLayout) {
                ReaderLayout.TOP_BOTTOM -> SplitPanes(env, numbered = false)
                ReaderLayout.NUMBERED -> SplitPanes(env, numbered = true)
                ReaderLayout.SIDE_BY_SIDE -> SideBySide(env)
                ReaderLayout.INTERLINEAR -> Interlinear(env)
            }
        }

        // ---- action bar for the highlighted sentence
        st.highlighted?.let { idx ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${idx + 1} / $n", style = captionStyle(c.ink), modifier = Modifier.padding(start = 8.dp).weight(1f))
                PolyIconButton(PolyIcons.Up, "Previous sentence", onClick = { if (idx > 0) vm.highlight(idx - 1) })
                PolyIconButton(PolyIcons.Down, "Next sentence", onClick = { if (idx < n - 1) vm.highlight(idx + 1) })
                TextButton(onClick = { vm.speak(a, env.text(idx, a)) }) { Text("▶ ${a.short}", color = c.accent) }
                TextButton(onClick = { vm.speak(b, env.text(idx, b)) }) { Text("▶ ${b.short}", color = c.accent) }
                PolyIconButton(PolyIcons.Pencil, "Rehearse this sentence", onClick = { onRehearse(story.story.id, idx, a) })
                PolyIconButton(PolyIcons.Cards, "Add sentence to Review", onClick = { vm.addSentenceToReview(idx, a, b) })
            }
        }
        st.message?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(1800); vm.clearMessage() }
            Text(it, style = captionStyle(c.success), modifier = Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }

    st.popup?.let { p ->
        val open: (Lang, String) -> Unit = { l, w -> vm.dismissPopup(); onOpenWord(l, w) }
        val style = when {
            p.expanded -> PopupLayout.SHEET
            settings.readerPopup == PopupLayout.TOOLTIP && p.anchor == null -> PopupLayout.FLOATING
            else -> settings.readerPopup
        }
        when (style) {
            PopupLayout.SHEET -> {
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = vm::dismissPopup, sheetState = sheet, containerColor = c.ground) {
                    WordSheet(vm, p, env, onOpenWord = open)
                }
            }
            PopupLayout.FLOATING -> Dialog(onDismissRequest = vm::dismissPopup) {
                val shape = RoundedCornerShape(Diacritica.shapes.card.coerceAtLeast(12.dp))
                Box(
                    Modifier.widthIn(max = 440.dp).heightIn(max = 600.dp).clip(shape).background(c.ground)
                        .border(1.dp, c.line, shape).padding(top = 18.dp),
                ) { WordSheet(vm, p, env, onOpenWord = open) }
            }
            PopupLayout.TOOLTIP -> {
                val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
                Popup(
                    popupPositionProvider = AnchorPosition(p.anchor!!, gap),
                    onDismissRequest = vm::dismissPopup,
                    properties = PopupProperties(focusable = true),
                ) { WordTooltip(vm, p, env) }
            }
        }
    }
}

// ----------------------------------------------------------------- layouts

/** Two stacked panes (top: language A, bottom: language B) whose scrolling follows each other. */
@Composable
private fun SplitPanes(env: SentenceEnv, numbered: Boolean) {
    val top = rememberLazyListState(initialFirstVisibleItemIndex = (env.highlighted ?: env.story.story.lastSentence).coerceAtLeast(0))
    val bottom = rememberLazyListState(initialFirstVisibleItemIndex = (env.highlighted ?: env.story.story.lastSentence).coerceAtLeast(0))
    SyncScroll(top, bottom)
    FollowHighlight(env.highlighted, top, bottom)
    // Drag the divider to give either language more room; double-tap it to split evenly again.
    var frac by remember(env.split) { mutableStateOf(env.split) }
    var height by remember { mutableStateOf(1f) }
    val c = Diacritica.colors
    Column(Modifier.fillMaxSize().onGloballyPositioned { height = it.size.height.toFloat().coerceAtLeast(1f) }) {
        Pane(env, env.a, top, numbered, Modifier.weight(frac))
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragEnd = { env.vm.setSplit(frac) }) { change, dy ->
                        change.consume()
                        frac = (frac + dy / height).coerceIn(0.2f, 0.8f)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { frac = 0.5f; env.vm.setSplit(0.5f) }) }
                .semantics { contentDescription = "Divider between the two languages. Drag to resize; double-tap to reset." }
                .padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LangBadge(env.b)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { Hairline() }
            Box(Modifier.padding(horizontal = 10.dp).width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.inkMuted.copy(alpha = 0.5f)))
            Box(Modifier.weight(1f)) { Hairline() }
        }
        Pane(env, env.b, bottom, numbered, Modifier.weight(1f - frac))
    }
}

@Composable
private fun Pane(env: SentenceEnv, lang: Lang, state: LazyListState, numbered: Boolean, modifier: Modifier) {
    val c = Diacritica.colors
    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(horizontal = Diacritica.spacing.screen, vertical = 10.dp)) {
        itemsIndexed(env.story.sentences) { idx, _ ->
            val on = env.highlighted == idx
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = if (numbered) 3.dp else 6.dp),
            ) {
                if (numbered) {
                    Text(
                        "${idx + 1}", style = captionStyle(if (on) c.accent else c.inkMuted).copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.width(26.dp).padding(top = 4.dp),
                    )
                }
                Sentence(env, idx, lang, readingSize(), Modifier.weight(1f))
            }
        }
    }
}

/** One scrolling list, one row per sentence: A on the left, B on the right. */
@Composable
private fun SideBySide(env: SentenceEnv) {
    val c = Diacritica.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = env.story.story.lastSentence.coerceAtLeast(0))
    FollowHighlight(env.highlighted, state)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp)) {
            Box(Modifier.weight(1f)) { LangBadge(env.a) }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { LangBadge(env.b) }
        }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(horizontal = Diacritica.spacing.screen, vertical = 4.dp)) {
            itemsIndexed(env.story.sentences) { idx, _ ->
                val on = env.highlighted == idx
                Column {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp))
                            .background(if (on) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Sentence(env, idx, env.a, 16.sp, Modifier.weight(1f))
                        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).fillMaxHeight().background(c.line))
                        Sentence(env, idx, env.b, 16.sp, Modifier.weight(1f))
                    }
                    Hairline()
                }
            }
        }
    }
}

/** Language A in reading size, language B as a quieter line under each sentence. */
@Composable
private fun Interlinear(env: SentenceEnv) {
    val c = Diacritica.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = env.story.story.lastSentence.coerceAtLeast(0))
    FollowHighlight(env.highlighted, state)
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(horizontal = Diacritica.spacing.screen, vertical = 12.dp)) {
        itemsIndexed(env.story.sentences) { idx, _ ->
            val on = env.highlighted == idx
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Sentence(env, idx, env.a, readingSize(), Modifier.fillMaxWidth())
                Gap(3.dp)
                Row {
                    Box(Modifier.padding(top = 4.dp, end = 8.dp).width(2.dp).height(14.dp).background(c.accent.copy(alpha = 0.5f)))
                    Sentence(env, idx, env.b, 15.sp, Modifier.weight(1f), muted = true)
                }
            }
        }
    }
}

@Composable
private fun readingSize(): TextUnit = (Diacritica.type.body.value + 3).sp

/** Scrolling one pane moves the other to the same sentence. Only the pane the user drags leads. */
@Composable
private fun SyncScroll(a: LazyListState, b: LazyListState) {
    val aDragged by a.interactionSource.collectIsDraggedAsState()
    val bDragged by b.interactionSource.collectIsDraggedAsState()
    var leader by remember { mutableStateOf(0) }
    LaunchedEffect(aDragged) { if (aDragged) leader = 1 }
    LaunchedEffect(bDragged) { if (bDragged) leader = 2 }
    LaunchedEffect(a, b) {
        snapshotFlow { a.firstVisibleItemIndex }.collect { if (leader == 1 && b.firstVisibleItemIndex != it) b.scrollToItem(it) }
    }
    LaunchedEffect(a, b) {
        snapshotFlow { b.firstVisibleItemIndex }.collect { if (leader == 2 && a.firstVisibleItemIndex != it) a.scrollToItem(it) }
    }
}

/** Brings a newly highlighted sentence into view in every list (when it is off screen). */
@Composable
private fun FollowHighlight(idx: Int?, vararg states: LazyListState) {
    LaunchedEffect(idx) {
        if (idx == null) return@LaunchedEffect
        states.forEach { s ->
            val visible = s.layoutInfo.visibleItemsInfo
            val fully = visible.filter { it.offset >= s.layoutInfo.viewportStartOffset && it.offset + it.size <= s.layoutInfo.viewportEndOffset }
            if (fully.none { it.index == idx }) s.animateScrollToItem((idx - 1).coerceAtLeast(0))
        }
    }
}

// ----------------------------------------------------------------- sentences

@Composable
private fun Sentence(env: SentenceEnv, idx: Int, lang: Lang, size: TextUnit, modifier: Modifier, muted: Boolean = false) {
    val c = Diacritica.colors
    val text = env.text(idx, lang)
    val style = TextStyle(
        fontFamily = Diacritica.fonts.reading, fontSize = size, lineHeight = (size.value * 1.5f).sp,
        color = if (muted) c.inkMuted else c.ink,
        fontStyle = if (muted && lang != Lang.ZH) FontStyle.Italic else FontStyle.Normal,
    )
    val mark = env.popup?.takeIf { it.sentenceIdx == idx && it.lang == lang }?.token
    // The translation of the pressed word, in the other language (underlined).
    val match = env.popup?.takeIf { it.sentenceIdx == idx && it.matchLang == lang }?.match
    val onTap = { env.vm.highlight(idx) }
    val onWord: (String, WordAnchor?) -> Unit = { w, a -> env.vm.lookUpWord(lang, w, idx, env.other(lang), a) }
    if (env.hideOther && lang == env.b && idx !in env.revealed) {
        Text(
            "Tap to show the translation",
            style = style.copy(color = c.inkMuted, fontStyle = FontStyle.Italic, fontSize = (size.value * 0.85f).sp),
            modifier = modifier.clip(RoundedCornerShape(4.dp)).background(c.surfaceAlt)
                .clickable { env.vm.reveal(idx) }.padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { contentDescription = "Translation hidden. Tap to show it." },
        )
        return
    }
    if (lang == Lang.ZH) {
        ZhSentence(env, text, style, mark ?: match, onTap, onWord, modifier, matchOnly = mark == null && match != null)
    } else {
        PlainSentence(text, lang, style, mark, onTap, onWord, modifier, match = match, tapLookup = env.tapLookup)
    }
}

@Composable
private fun PlainSentence(
    text: String, lang: Lang, style: TextStyle, mark: String?,
    onTap: () -> Unit, onWord: (String, WordAnchor?) -> Unit, modifier: Modifier,
    match: String? = null,
    tapLookup: Boolean = false,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val markColor = Diacritica.colors.accent.copy(alpha = 0.22f)
    val matchColor = Diacritica.colors.accent
    val haptics = LocalHapticFeedback.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    val word by rememberUpdatedState(onWord)
    val annotated = remember(text, mark, match, markColor) {
        buildAnnotatedString {
            append(text)
            if (!mark.isNullOrEmpty()) {
                val i = text.indexOf(mark)
                if (i >= 0) addStyle(SpanStyle(background = markColor), i, i + mark.length)
            }
            if (!match.isNullOrEmpty()) {
                val i = text.indexOf(match)
                if (i >= 0) addStyle(
                    SpanStyle(color = matchColor, fontWeight = FontWeight.SemiBold, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    i, i + match.length,
                )
            }
        }
    }
    Text(
        annotated, style = style, onTextLayout = { layout = it },
        modifier = modifier.onGloballyPositioned { coords = it }.pointerInput(text, lang, tapLookup) {
            fun lookAt(pos: Offset, haptic: Boolean) {
                val l = layout ?: return
                val off = l.getOffsetForPosition(pos)
                val w = wordAt(text, off, lang) ?: return
                if (haptic) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val line = l.getLineForOffset(off)
                val anchor = coords?.let { c ->
                    val top = c.localToWindow(Offset(pos.x, l.getLineTop(line)))
                    val bottom = c.localToWindow(Offset(pos.x, l.getLineBottom(line)))
                    WordAnchor(top.x, top.y, bottom.y)
                }
                word(w, anchor)
            }
            detectTapGestures(
                // Default: tap highlights the sentence, long-press looks up the word. "Tap a word" swaps them.
                onTap = { pos -> if (tapLookup) lookAt(pos, haptic = false) else tap() },
                onLongPress = { pos -> if (tapLookup) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); tap() } else lookAt(pos, haptic = true) },
            )
        },
    )
}

/** The word around [offset], with surrounding punctuation and French/Italian elision (l', dell') removed. */
internal fun wordAt(text: String, offset: Int, lang: Lang): String? {
    if (text.isEmpty()) return null
    fun isWordChar(ch: Char) = ch.isLetterOrDigit() || ch == '\'' || ch == '’' || ch == '-'
    var i = offset.coerceIn(0, text.length - 1)
    if (!isWordChar(text[i]) && i > 0 && isWordChar(text[i - 1])) i -= 1
    if (!isWordChar(text[i])) return null
    var s = i
    var e = i + 1
    while (s > 0 && isWordChar(text[s - 1])) s--
    while (e < text.length && isWordChar(text[e])) e++
    var w = text.substring(s, e).trim('\'', '’', '-')
    if (lang == Lang.FR || lang == Lang.IT) {
        val cut = w.indexOfFirst { it == '\'' || it == '’' }
        if (cut in 1..4) w = w.substring(cut + 1)
    }
    return w.takeIf { it.any(Char::isLetter) }
}

/** Mandarin: segmented words, each with its pinyin above (when on). Long-press a word to look it up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZhSentence(
    env: SentenceEnv, text: String, style: TextStyle, mark: String?,
    onTap: () -> Unit, onWord: (String, WordAnchor?) -> Unit, modifier: Modifier,
    matchOnly: Boolean = false,
) {
    val c = Diacritica.colors
    val haptics = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val word by rememberUpdatedState(onWord)
    var tokens by remember(text) { mutableStateOf(emptyList<app.diacritica.data.DictionaryRepository.ZhToken>()) }
    LaunchedEffect(text) { tokens = env.vm.zhTokens(text) }
    if (tokens.isEmpty()) {
        Text(text, style = style, modifier = modifier.pointerInput(text) { detectTapGestures(onTap = { tap() }) })
        return
    }
    val pinyinStyle = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = (style.fontSize.value * 0.58f).sp, color = c.accent)
    // Only reserve a pinyin row when at least one word in the sentence gets pinyin.
    val pinyinRow = remember(tokens, env.pinyinMode, env.pinyinTop) { tokens.any { env.pinyinFor(it) != null } }
    FlowRow(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tokens.forEachIndexed { i, t -> key(text, i) {
            val shown = if (env.traditional) t.traditional ?: t.text else t.text
            // A dictionary word, or any Chinese character the dictionary couldn't place (looked up on its own).
            val isWord = t.pinyin != null || TextNorm.containsHan(t.text)
            var tc by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Column(
                Modifier
                    .onGloballyPositioned { tc = it }
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isWord && !matchOnly && mark == t.text) c.accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                    .then(
                        // The translation of a word pressed in the other language: an accent underline.
                        if (matchOnly && isWord && mark != null && (t.text in mark || mark in t.text)) Modifier.drawBehind {
                            drawRect(c.accent, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                        } else Modifier,
                    )
                    .pointerInput(text, i, t.text, isWord, env.tapLookup) {
                        fun lookUp(haptic: Boolean) {
                            if (!isWord) return
                            if (haptic) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val anchor = tc?.let { lc ->
                                val topLeft = lc.localToWindow(Offset.Zero)
                                WordAnchor(topLeft.x + lc.size.width / 2f, topLeft.y, topLeft.y + lc.size.height)
                            }
                            word(t.text, anchor)
                        }
                        detectTapGestures(
                            onTap = { if (env.tapLookup && isWord) lookUp(haptic = false) else tap() },
                            onLongPress = { if (env.tapLookup) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); tap() } else lookUp(haptic = true) },
                        )
                    }
                    .padding(horizontal = if (isWord) 1.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pinyinRow) Text(env.pinyinFor(t) ?: "", style = pinyinStyle, maxLines = 1)
                Text(shown, style = style.copy(lineHeight = (style.fontSize.value * 1.25f).sp))
            }
        } }
    }
}

// ----------------------------------------------------------------- word popup

/** Places the tooltip under the word (or above it near the bottom of the screen), kept inside the window. */
private class AnchorPosition(private val a: WordAnchor, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val x = (a.x - popupContentSize.width / 2f).toInt().coerceIn(gap, maxX)
        val below = a.bottom.toInt() + gap
        val y = if (below + popupContentSize.height <= windowSize.height - gap) below
        else (a.top.toInt() - gap - popupContentSize.height).coerceAtLeast(gap)
        return IntOffset(x, y)
    }
}

/** Tooltip: the essentials next to the word; "More" opens the full sheet. */
@Composable
private fun WordTooltip(vm: ReaderViewModel, p: WordPopup, env: SentenceEnv) {
    val c = Diacritica.colors
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val r = p.result
    val hw: WordInfo = r?.headword ?: WordInfo(p.lang, p.token)
    val found = r != null && !r.isEmpty
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Column(
        Modifier.widthIn(min = 200.dp, max = 300.dp).shadow(8.dp, shape).clip(shape).background(c.ground)
            .border(1.dp, c.line, shape).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val head = hw.article?.let { a -> if (a.endsWith("'")) a + hw.word else "$a ${hw.word}" } ?: hw.word
                Text(if (p.lang == Lang.ZH && env.traditional) hw.traditional ?: head else head, style = headwordStyle(19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(hw.pinyin, hw.pos, hw.gender).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = captionStyle(if (hw.pinyin != null) c.accent else c.inkMuted), maxLines = 1)
                FormNotes(p.token, r?.forms.orEmpty(), onLemma = { l -> vm.lookUpWord(p.lang, l, p.sentenceIdx, env.other(p.lang), p.anchor) }, max = 1)
            }
            PolyIconButton(PolyIcons.Speaker, "Pronounce", onClick = { vm.speakWord(hw) })
        }
        when {
            p.loading -> Text("Looking up…", style = captionStyle(), modifier = Modifier.padding(vertical = 4.dp))
            p.fetching -> Text("Downloading…", style = captionStyle(), modifier = Modifier.padding(vertical = 4.dp))
            !found -> Text("Not in the offline dictionary.", style = captionStyle(), modifier = Modifier.padding(vertical = 4.dp))
            else -> {
                // The other Reader language and English first, then your other languages.
                val order = (listOf(env.other(p.lang), Lang.EN) + r!!.results.map { it.lang }).distinct().filter { it != p.lang }
                order.forEach { l ->
                    val w = r!!.results.firstOrNull { it.lang == l }?.words?.firstOrNull() ?: return@forEach
                    val base = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.widthIn(min = 34.dp).padding(end = 10.dp)) { LangBadge(l) }
                        Text(base + (w.pinyin?.let { "  $it" } ?: ""), style = bodyStyle(size = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!p.loading) vm.falseFriends(p, env.other(p.lang)).firstOrNull()?.let {
            FalseFriendNote(it, Modifier.padding(top = 6.dp, end = 10.dp), compact = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (found) {
                val isSaved = "${hw.lang.code}:${hw.word}" in saved
                PolyIconButton(
                    if (isSaved) PolyIcons.StarFilled else PolyIcons.Star, if (isSaved) "Remove from saved" else "Save word",
                    onClick = { vm.toggleSave(hw) }, tint = if (isSaved) c.savedStar else c.inkMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("More ›", style = captionStyle(c.accent), modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.expandPopup() }.padding(horizontal = 10.dp, vertical = 12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordSheet(vm: ReaderViewModel, p: WordPopup, env: SentenceEnv, onOpenWord: (Lang, String) -> Unit) {
    val c = Diacritica.colors
    val uri = LocalUriHandler.current
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val r = p.result
    val hw: WordInfo = r?.headword ?: WordInfo(p.lang, p.token)
    val found = r != null && !r.isEmpty
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangBadge(p.lang, boxed = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val head = hw.article?.let { a -> if (a.endsWith("'")) a + hw.word else "$a ${hw.word}" } ?: hw.word
                val shownHead = if (p.lang == Lang.ZH && env.traditional) hw.traditional ?: head else head
                Text(shownHead, style = headwordStyle(Diacritica.type.headwordLarge))
                if (p.lang == Lang.ZH) {
                    val extra = listOfNotNull(hw.pinyin, hw.traditional?.takeIf { it != hw.word && !env.traditional }, hw.hsk?.let { ZhRanking.hskLabel(it) })
                    if (extra.isNotEmpty()) Text(extra.joinToString("  ·  "), style = captionStyle(c.accent))
                }
            }
            PolyIconButton(PolyIcons.Speaker, "Pronounce", onClick = { vm.speakWord(hw) })
            if (found) {
                val isSaved = "${hw.lang.code}:${hw.word}" in saved
                PolyIconButton(
                    if (isSaved) PolyIcons.StarFilled else PolyIcons.Star, if (isSaved) "Remove from saved" else "Save word",
                    onClick = { vm.toggleSave(hw) }, tint = if (isSaved) c.savedStar else c.inkMuted,
                )
            }
        }
        val meta = listOfNotNull(hw.pos, hw.gender, hw.plural?.let { "pl. $it" }, hw.ipa?.let { "/$it/" }).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = captionStyle(), modifier = Modifier.padding(top = 4.dp))
        val forms = r?.forms.orEmpty()
        if (forms.isNotEmpty()) {
            FormNotes(p.token, forms, onLemma = { l -> vm.lookUpWord(p.lang, l, p.sentenceIdx, env.other(p.lang), p.anchor) }, modifier = Modifier.padding(top = 4.dp))
        } else if (r?.redirectedFrom != null || (found && !hw.word.equals(p.token, ignoreCase = true))) {
            Text("“${p.token}” is a form of ${hw.word}", style = captionStyle(c.ink), modifier = Modifier.padding(top = 4.dp))
        }
        hw.gloss?.let { Text(it, style = bodyStyle(), modifier = Modifier.padding(top = 8.dp)) }
        if (!p.loading) vm.falseFriends(p, env.other(p.lang)).forEach {
            FalseFriendNote(it, Modifier.padding(top = 10.dp))
        }

        when {
            p.loading -> { Gap(14.dp); Text("Looking up…", style = captionStyle()) }
            p.fetching -> { Gap(14.dp); Text("Not in the offline dictionary. Downloading it…", style = captionStyle()) }
            !found -> { Gap(14.dp); Text("Not in the offline dictionary.", style = captionStyle()) }
            else -> {
                val concept = r!!.concepts.getOrNull(r.selected)
                Gap(14.dp)
                SectionLabel(concept?.label?.let { "Meaning · $it" } ?: "Translations")
                Gap(6.dp)
                val order = listOf(env.other(p.lang), Lang.EN).distinct().filter { it != p.lang }
                val rows = r.results.filter { it.lang != p.lang }.sortedBy { lr -> order.indexOf(lr.lang).let { if (it < 0) 99 else it } }
                rows.forEach { lr ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(lr.lang) }
                        val words = lr.words.take(3).joinToString(", ") { w ->
                            val base = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
                            val shown = if (w.lang == Lang.ZH && env.traditional) w.traditional ?: base else base
                            if (w.lang == Lang.ZH && w.pinyin != null) "$shown ${w.pinyin}" else shown
                        }
                        Text(words.ifEmpty { "—" }, style = bodyStyle(if (words.isEmpty()) c.inkMuted else c.ink), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // The sentence it came from, in both languages.
        val src = env.text(p.sentenceIdx, p.lang)
        val tr = env.text(p.sentenceIdx, env.other(p.lang))
        if (src.isNotEmpty()) {
            Gap(14.dp)
            Hairline()
            Gap(10.dp)
            Text(src, style = bodyStyle(size = 14.sp))
            if (tr.isNotEmpty() && env.other(p.lang) != p.lang) Text(tr, style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = FontStyle.Italic))
        }

        Gap(16.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (found) PolyButton("Open in Dictionary", onClick = { onOpenWord(hw.lang, hw.word) })
            PolyButton(
                "${OfficialDictionary.name(p.lang)} ↗",
                onClick = { runCatching { uri.openUri(OfficialDictionary.url(p.lang, hw.word, hw.traditional)) } },
                primary = false,
            )
        }
    }
}

/** "拼音 ▾": pinyin for every word, only for uncommon characters (outside the top 500–3,000), or off. */
@Composable
private fun PinyinMenu(mode: PinyinMode, top: Int, onPick: (PinyinMode, Int?) -> Unit) {
    val c = Diacritica.colors
    var open by remember { mutableStateOf(false) }
    Box {
        PolyChip(
            when (mode) { PinyinMode.ALL -> "拼音"; PinyinMode.RARE -> "拼音 · rare"; PinyinMode.OFF -> "拼音 off" },
            selected = mode != PinyinMode.OFF, onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            DropdownMenuItem(
                text = { Text("Pinyin on every word", style = bodyStyle(if (mode == PinyinMode.ALL) c.accent else c.ink)) },
                onClick = { open = false; onPick(PinyinMode.ALL, null) },
            )
            listOf(500, 1000, 2000, 3000).forEach { n ->
                val on = mode == PinyinMode.RARE && top == n
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Only uncommon characters", style = bodyStyle(if (on) c.accent else c.ink))
                            Text("outside the ${"%,d".format(n)} most common", style = captionStyle())
                        }
                    },
                    onClick = { open = false; onPick(PinyinMode.RARE, n) },
                )
            }
            DropdownMenuItem(
                text = { Text("No pinyin", style = bodyStyle(if (mode == PinyinMode.OFF) c.accent else c.ink)) },
                onClick = { open = false; onPick(PinyinMode.OFF, null) },
            )
        }
    }
}

/** Play: read the story aloud from the highlighted sentence (one language or both); Stop while playing. */
@Composable
private fun ReadAloudButton(playing: Boolean, a: Lang, b: Lang, onPlay: (List<Lang>) -> Unit, onStop: () -> Unit) {
    val c = Diacritica.colors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // Android 13+: ask once to show the playback notification (it has the Stop button when the screen is off).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start(langs: List<Lang>) {
        open = false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        onPlay(langs)
    }
    Box {
        if (playing) {
            PolyIconButton(PolyIcons.Stop, "Stop reading aloud", onStop, tint = c.accent)
        } else {
            PolyIconButton(PolyIcons.Play, "Read the story aloud", { open = true }, tint = c.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            Text("Read aloud from the highlighted sentence", style = captionStyle(), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp))
            DropdownMenuItem(text = { Text(a.englishName, style = bodyStyle()) }, onClick = { start(listOf(a)) })
            if (b != a) {
                DropdownMenuItem(text = { Text(b.englishName, style = bodyStyle()) }, onClick = { start(listOf(b)) })
                DropdownMenuItem(text = { Text("${a.englishName}, then ${b.englishName}", style = bodyStyle()) }, onClick = { start(listOf(a, b)) })
            }
        }
    }
}
