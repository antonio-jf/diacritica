@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.diacritica.ui.books

import app.diacritica.ui.theme.hanTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalBottomSheet
import app.diacritica.ui.components.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.DictionaryRepository
import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import app.diacritica.data.WordInfo
import app.diacritica.data.ZhRanking
import app.diacritica.mybooks.BookParagraph
import app.diacritica.reader.OfficialDictionary
import app.diacritica.settings.AppSettings
import app.diacritica.settings.PinyinMode
import app.diacritica.ui.components.ChoiceMenuItem
import app.diacritica.ui.components.FormNotes
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.MenuSection
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.reader.wordAt
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.Fonts
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.BreakIterator

/** Reading one of your books: one chapter at a time, tap a word for the dictionary. */
@Composable
fun BookReaderScreen(
    vm: BooksViewModel, settings: AppSettings, bookId: Long, onBack: () -> Unit, onOpenWord: (Lang, String) -> Unit,
    /** Rehearse a passage: text, its language, where it's from, and an English translation if there is one. */
    onRehearse: (String, Lang, String, String?) -> Unit = { _, _, _, _ -> },
) {
    val c = Diacritica.colors
    val st by vm.reader.collectAsStateWithLifecycle()
    val word by vm.word.collectAsStateWithLifecycle()
    val wordMarks by vm.wordMarks.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val visibleP by vm.visibleParagraph.collectAsStateWithLifecycle()
    LaunchedEffect(bookId) { vm.open(bookId) }
    // The normal Android selection, with the app's own toolbar (see BookSelection.kt).
    val realClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val clipboard = remember(realClipboard) { CapturingClipboard(realClipboard) }
    val toolbar = remember { SelectionToolbar() }
    val selecting = toolbar.request != null
    var notesOpen by remember { mutableStateOf(false) }
    val book = st.book
    val lang = book?.lang?.let { Lang.fromCode(it) } ?: Lang.EN
    var toc by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var textMenu by remember { mutableStateOf(false) }

    // The app's bottom bar is hidden while reading, so this screen keeps clear of Android's navigation bar itself.
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        // ---------------------------------------------------------- top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(enabled = st.chapters.isNotEmpty()) { toc = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(book?.title ?: "", style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    st.chapters.getOrNull(st.chapter)?.title ?: "",
                    style = bodyStyle(size = 15.sp).copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val bookmarked = marks.any { it.chapter == st.chapter && it.paragraph == visibleP }
            PolyIconButton(
                if (bookmarked) PolyIcons.BookmarkFilled else PolyIcons.Bookmark, if (bookmarked) "Remove bookmark" else "Bookmark this page",
                vm::toggleBookmark, tint = if (bookmarked) c.accent else c.ink,
            )
            PolyIconButton(PolyIcons.Note, "Notes and bookmarks", { notesOpen = true }, tint = if (notes.isNotEmpty() || marks.isNotEmpty()) c.accent else c.ink)
            PolyIconButton(PolyIcons.Toc, "Contents", { toc = true }, tint = c.ink)
            PolyIconButton(PolyIcons.Search, "Search in book", { search = true }, tint = c.ink)
            Box {
                PolyIconButton(PolyIcons.TextSize, "Text settings", { textMenu = true }, tint = c.ink)
                TextMenu(textMenu, onDismiss = { textMenu = false }, settings, vm, isZh = lang == Lang.ZH)
            }
        }
        Hairline()

        if (st.loading || book == null) {
            Text(if (st.loading) "Opening…" else "This book is no longer on your shelf.", style = captionStyle(), modifier = Modifier.padding(20.dp))
            return@Column
        }

        // ---------------------------------------------------------- chapter strip (sticky): previous · next
        val prev = st.chapters.getOrNull(st.chapter - 1)
        val next = st.chapters.getOrNull(st.chapter + 1)
        if (prev != null || next != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    prev?.let {
                        Text(
                            "‹ ${it.title}", style = captionStyle(c.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.goToChapter(st.chapter - 1) }.padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    next?.let {
                        Text(
                            "${it.title} ›", style = captionStyle(c.accent).copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.goToChapter(st.chapter + 1) }.padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Hairline()
        }

        // ---------------------------------------------------------- text
        val list = rememberLazyListState()
        // Index 0 is the "previous chapter" row, so paragraph i is item i + 1.
        var restored by remember { mutableStateOf(-1) }
        LaunchedEffect(st.chapter, st.jump) {
            list.scrollToItem((st.scrollTo + 1).coerceAtMost(st.paragraphs.size))
            restored = st.jump * 10_000 + st.chapter
        }
        LaunchedEffect(list, restored) {
            if (restored < 0) return@LaunchedEffect
            snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { i -> vm.visible((i - 1).coerceAtLeast(0)) }
        }
        val size = settings.bookTextSize.sp
        val style = TextStyle(
            fontFamily = if (settings.bookSerif) Fonts.sourceSerif else Fonts.inter,
            fontSize = size, lineHeight = (settings.bookTextSize * settings.bookLineSpacing).sp, color = c.ink,
        )
        // Swipe left for the next chapter, right for the previous one. The page follows the finger a little.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val threshold = with(density) { 96.dp.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        val chapterNow by rememberUpdatedState(st.chapter)
        val chapterCount by rememberUpdatedState(st.chapters.size)
        // Bumping this rebuilds the selection container, which drops the selection.
        var selectionEpoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                // With text selected, a tap anywhere on the page (a word or blank space) just clears the selection.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                        )
                        if (toolbar.request == null) return@awaitEachGesture
                        val up = waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            ?: return@awaitEachGesture
                        val moved = (up.position - down.position).getDistance()
                        if (moved < viewConfiguration.touchSlop && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                            up.consume()
                            toolbar.hide()
                            selectionEpoch++
                        }
                    }
                }
                .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            toolbar.request != null -> {}
                            drag < -threshold && chapterNow + 1 < chapterCount -> vm.goToChapter(chapterNow + 1)
                            drag > threshold && chapterNow > 0 -> vm.goToChapter(chapterNow - 1)
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                    onHorizontalDrag = { change, amount -> drag += amount; change.consume() },
                )
            },
        ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalTextToolbar provides toolbar,
            androidx.compose.ui.platform.LocalClipboardManager provides clipboard,
        ) { androidx.compose.runtime.key(selectionEpoch) { androidx.compose.foundation.text.selection.SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = drag * 0.45f
                alpha = 1f - (kotlin.math.abs(drag) / (threshold * 4)).coerceAtMost(0.35f)
            },
            state = list, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
        ) {
            // Index 0 stays a spacer, so paragraph i is item i + 1.
            item(key = "top-${st.chapter}") { Gap(8.dp) }
            itemsIndexed(st.paragraphs, key = { _, p -> "${p.chapter}-${p.idx}" }) { _, p ->
                Paragraph(
                    p, lang, style, size, wordMarks, settings, vm,
                    highlights = highlights["${st.chapter}:${p.idx}"].orEmpty(),
                    mark = word?.takeIf { it.sentence.isNotEmpty() && p.text.contains(it.sentence) }?.token,
                    selected = null, selecting = selecting,
                )
            }
            item(key = "end-${st.chapter}") {
                androidx.compose.foundation.text.selection.DisableSelection {
                    Text(
                        if (next != null) "End of chapter · swipe left for the next" else "The end",
                        style = captionStyle(), modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        } } }
        // The app's selection toolbar, next to the selected text.
        toolbar.request?.let { r ->
            val gap = with(density) { 8.dp.roundToPx() }
            fun take(action: (String) -> Unit) {
                val text = r.copy?.let { clipboard.capture(it) }?.trim()
                toolbar.hide()
                if (!text.isNullOrEmpty()) action(text)
            }
            val source = "From “${book.title}”"
            // Reading the selection ends it (Compose's copy releases it), so the text is taken only once an action is tapped.
            val actions = buildList {
                add(ToolbarAction(PolyIcons.Explain, "Explain") { take { t -> vm.useText(t); vm.openPanel(breakdown = true) } })
                add(ToolbarAction(PolyIcons.Translate, "Translate") { take { t -> vm.useText(t); vm.openPanel(breakdown = false) } })
                add(ToolbarAction(PolyIcons.Pencil, "Rehearse") { take { t -> onRehearse(t, lang, source, null) } })
                add(ToolbarAction(PolyIcons.Marker, "Highlight") { take { t -> vm.useText(t); vm.highlightSelection() } })
                add(ToolbarAction(PolyIcons.Copy, "Copy") { take { t -> clipboard.setReal(t) } })
            }
            androidx.compose.ui.window.Popup(
                popupPositionProvider = AboveSelection(r.rect, gap),
                properties = androidx.compose.ui.window.PopupProperties(focusable = false),
            ) { SelectionPill(actions) }
        }
        // What letting go will do, once the swipe is far enough.
        val target = when {
            drag < -threshold -> next?.title
            drag > threshold -> prev?.title
            else -> null
        }
        target?.let {
            Text(
                (if (drag < 0) "Next: " else "Previous: ") + it, style = captionStyle(c.ground), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(c.accent).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        }
        st.message?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); vm.clearReaderMessage() }
            Text(msg, style = captionStyle(c.accent), modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp))
        }
        // ---------------------------------------------------------- progress line
        val first by remember { androidx.compose.runtime.derivedStateOf { (list.firstVisibleItemIndex - 1).coerceAtLeast(0) } }
        val start = st.chapters.getOrNull(st.chapter)?.start ?: 0
        val pct = if (book.paragraphs > 0) ((start + first + 1) * 100 / book.paragraphs).coerceIn(0, 100) else 0
        Text(
            "Chapter ${st.chapter + 1} of ${st.chapters.size} · $pct%",
            style = captionStyle(), modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
        )
    }

    // ---------------------------------------------------------- sheets
    if (toc) {
        ModalBottomSheet(onDismissRequest = { toc = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
            Contents(st.chapters.map { it.title }, st.chapter) { i -> toc = false; vm.goToChapter(i) }
        }
    }
    if (search) {
        ModalBottomSheet(onDismissRequest = { search = false; vm.closeSearch() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
            SearchSheet(vm, st.searchResults, st.chapters.map { it.title }) { ch, idx -> search = false; vm.goToChapter(ch, idx) }
        }
    }
    if (notesOpen) {
        ModalBottomSheet(onDismissRequest = { notesOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
            NotesSheet(
                notes, marks, st.chapters.map { it.title },
                onOpen = { n -> notesOpen = false; vm.openNote(n) }, onDelete = vm::deleteNote,
                onOpenMark = { m -> notesOpen = false; vm.goToChapter(m.chapter, m.paragraph) }, onDeleteMark = vm::deleteMark,
            )
        }
    }
    panel?.let { p ->
        ModalBottomSheet(onDismissRequest = vm::closePanel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
            PassageSheet(
                vm, p, settings, lang,
                onRehearse = { text, en -> vm.closePanel(); vm.clearSelection(); onRehearse(text, lang, "From “${book?.title ?: "your book"}”", en) },
                onGoTo = { ch, idx -> vm.closePanel(); vm.goToChapter(ch, idx) },
            )
        }
    }
    word?.let { w ->
        ModalBottomSheet(onDismissRequest = vm::dismissWord, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = c.ground) {
            WordSheet(vm, w, onOpenWord = { l, x -> vm.dismissWord(); onOpenWord(l, x) })
        }
    }
}

// ------------------------------------------------------------------ paragraphs

private val wordPattern = Regex("[\\p{L}\\p{M}'’-]+")

/** The sentence of [text] around character [offset] (saved with a word as its context). */
internal fun sentenceAround(text: String, offset: Int, lang: Lang): String {
    if (text.isEmpty()) return ""
    val it = BreakIterator.getSentenceInstance(lang.locale)
    it.setText(text)
    val o = offset.coerceIn(0, text.length - 1)
    val end = it.following(o).let { e -> if (e == BreakIterator.DONE) text.length else e }
    val start = it.previous().let { s -> if (s == BreakIterator.DONE) 0 else s }
    return text.substring(start.coerceAtMost(end), end).trim().replace('\n', ' ')
}

@Composable
private fun Paragraph(
    p: BookParagraph, lang: Lang, style: TextStyle, size: TextUnit, marks: Map<String, Int>,
    settings: AppSettings, vm: BooksViewModel, highlights: List<String>, mark: String?, selected: IntRange?, selecting: Boolean,
) {
    // Highlighted passages as ranges of this paragraph.
    val hl = remember(p.text, highlights) {
        highlights.mapNotNull { h -> p.text.indexOf(h).takeIf { it >= 0 }?.let { it until it + h.length } }
    }
    val pStyle = if (p.heading) style.copy(fontSize = (size.value * 1.25f).sp, fontWeight = FontWeight.SemiBold, lineHeight = (size.value * 1.6f).sp) else style
    val m = Modifier.fillMaxWidth().padding(top = if (p.heading) 18.dp else 0.dp, bottom = if (p.heading) 12.dp else (size.value * 0.7f).dp)
    val onSelect: (IntRange) -> Unit = { r -> if (!r.isEmpty()) vm.select(p.idx, r.first, r.last + 1) }
    val onAdjust: (IntRange) -> Unit = { r -> if (!r.isEmpty()) vm.adjust(p.idx, r.first, r.last + 1) }
    if (lang == Lang.ZH) app.diacritica.ui.theme.ReadingText { ZhParagraph(p.text, pStyle, marks, settings, vm, mark, m, selected, selecting, onSelect, onAdjust, hl) }
    else PlainParagraph(p.text, lang, pStyle, marks, mark, m, selected, selecting, onSelect, onAdjust, hl) { w, offset -> vm.lookUp(w, sentenceAround(p.text, offset, lang)) }
}

@Composable
private fun PlainParagraph(
    text: String, lang: Lang, style: TextStyle, mine: Map<String, Int>, mark: String?, modifier: Modifier,
    selected: IntRange?, selecting: Boolean, onSelect: (IntRange) -> Unit, onAdjust: (IntRange) -> Unit,
    highlights: List<IntRange>,
    onWord: (String, Int) -> Unit,
) {
    val selColor = selectionColor()
    val hlColor = highlightColor()
    val selecting by rememberUpdatedState(selecting)
    val select by rememberUpdatedState(onSelect)
    val adjust by rememberUpdatedState(onAdjust)
    val c = Diacritica.colors
    val learning = c.warning.copy(alpha = 0.22f)
    val known = c.success.copy(alpha = 0.16f)
    val markColor = c.accent.copy(alpha = 0.22f)
    val haptics = LocalHapticFeedback.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val word by rememberUpdatedState(onWord)
    val annotated = remember(text, mine, mark, learning, selected, highlights) {
        buildAnnotatedString {
            append(text)
            highlights.forEach { r -> addStyle(SpanStyle(background = hlColor), r.first, (r.last + 1).coerceAtMost(text.length)) }
            if (selected != null && !selected.isEmpty()) addStyle(SpanStyle(background = selColor), selected.first, (selected.last + 1).coerceAtMost(text.length))
            if (mine.isNotEmpty()) for (mm in wordPattern.findAll(text)) {
                val w = mm.value
                val status = mine[TextNorm.normalize(w)] ?: w.substringAfterLast('\'').substringAfterLast('’')
                    .takeIf { it.length < w.length }?.let { mine[TextNorm.normalize(it)] }
                if (status != null) addStyle(SpanStyle(background = if (status >= 2) known else learning), mm.range.first, mm.range.last + 1)
            }
            if (!mark.isNullOrEmpty()) {
                Regex("(?<![\\p{L}])" + Regex.escape(mark) + "(?![\\p{L}])").find(text)?.let { r ->
                    addStyle(SpanStyle(background = markColor), r.range.first, r.range.last + 1)
                }
            }
        }
    }
    Text(
        annotated, style = style, onTextLayout = { layout = it },
        modifier = modifier.pointerInput(text, lang) {
            fun lookAt(pos: androidx.compose.ui.geometry.Offset, haptic: Boolean) {
                val l = layout ?: return
                val off = l.getOffsetForPosition(pos)
                val w = wordAt(text, off, lang) ?: return
                if (haptic) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                word(w, off)
            }
            // Tap looks a word up. Long-press belongs to the normal text selection (handles + the app's toolbar).
            detectTapGestures(onTap = { pos -> if (!selecting) lookAt(pos, false) })
        },
    )
}

/** Mandarin: dictionary words as tappable boxes, with pinyin above uncommon (or all) words per the Reader setting. */
@Composable
private fun ZhParagraph(
    text: String, style: TextStyle, mine: Map<String, Int>, settings: AppSettings, vm: BooksViewModel, mark: String?, modifier: Modifier,
    selected: IntRange?, selecting: Boolean, onSelect: (IntRange) -> Unit, onAdjust: (IntRange) -> Unit,
    highlights: List<IntRange>,
) {
    val c = Diacritica.colors
    val selColor = selectionColor()
    val hlColor = highlightColor()
    val haptics = LocalHapticFeedback.current
    var tokens by remember(text) { mutableStateOf(emptyList<DictionaryRepository.ZhToken>()) }
    LaunchedEffect(text) { tokens = vm.zhTokens(text) }
    if (tokens.isEmpty()) { Text(text, style = style, modifier = modifier); return }
    fun pinyinFor(t: DictionaryRepository.ZhToken): String? = when (settings.readerPinyinMode) {
        PinyinMode.ALL -> t.pinyin
        PinyinMode.RARE -> t.pinyin?.takeIf { vm.isRare(t.text, settings.readerPinyinTop) }
        PinyinMode.OFF -> null
    }
    val pinyinRow = remember(tokens, settings.readerPinyinMode, settings.readerPinyinTop) { tokens.any { pinyinFor(it) != null } }
    val pinyinStyle = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = (style.fontSize.value * 0.55f).sp, color = c.accent)
    // Character offset of each token, to find its sentence.
    val offsets = remember(tokens) { tokens.runningFold(0) { acc, t -> acc + t.text.length } }
    FlowRow(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tokens.forEachIndexed { i, t -> key(i) {
            val isWord = t.pinyin != null || TextNorm.containsHan(t.text)
            val status = mine[t.text] ?: 0
            val range = offsets[i] until offsets[i + 1]
            val inSelection = selected != null && range.first <= selected.last && range.last >= selected.first
            Column(
                Modifier.clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            inSelection -> selColor
                            highlights.any { h -> range.first <= h.last && range.last >= h.first } -> hlColor
                            isWord && mark == t.text -> c.accent.copy(alpha = 0.22f)
                            status >= 2 -> c.success.copy(alpha = 0.16f)
                            status == 1 -> c.warning.copy(alpha = 0.22f)
                            else -> Color.Transparent
                        },
                    )
                    .then(
                        if (isWord) Modifier.clickable(indication = null, interactionSource = null) {
                            if (!selecting) vm.lookUp(t.text, sentenceAround(text, offsets[i], Lang.ZH))
                        } else Modifier,
                    )
                    .padding(horizontal = if (isWord) 1.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Pinyin isn't part of the text: left out of selections.
                if (pinyinRow) androidx.compose.foundation.text.selection.DisableSelection { Text(pinyinFor(t) ?: "", style = pinyinStyle, maxLines = 1) }
                Text(t.text, style = style.copy(lineHeight = (style.fontSize.value * 1.3f).sp))
            }
        } }
    }
}

// ------------------------------------------------------------------ menus and sheets

@Composable
private fun TextMenu(expanded: Boolean, onDismiss: () -> Unit, s: AppSettings, vm: BooksViewModel, isZh: Boolean) {
    val c = Diacritica.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = c.ground, modifier = Modifier.widthIn(max = 320.dp)) {
        MenuSection("Text size")
        Chips {
            PolyChip("A−", false, { vm.setText(size = s.bookTextSize - 1) })
            Text("${s.bookTextSize}", style = bodyStyle(), modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 6.dp))
            PolyChip("A+", false, { vm.setText(size = s.bookTextSize + 1) })
        }
        MenuSection("Line spacing")
        Chips {
            listOf("Compact" to 1.35f, "Normal" to 1.6f, "Airy" to 1.9f).forEach { (label, v) ->
                PolyChip(label, kotlin.math.abs(s.bookLineSpacing - v) < 0.05f, { vm.setText(spacing = v) })
            }
        }
        MenuSection("Font")
        Chips {
            PolyChip("Serif", s.bookSerif, { vm.setText(serif = true) })
            PolyChip("Sans", !s.bookSerif, { vm.setText(serif = false) })
        }
        if (isZh) {
            MenuSection("Pinyin")
            Chips {
                PolyChip("All", s.readerPinyinMode == PinyinMode.ALL, { vm.setPinyinMode(PinyinMode.ALL) })
                PolyChip("Uncommon", s.readerPinyinMode == PinyinMode.RARE, { vm.setPinyinMode(PinyinMode.RARE) })
                PolyChip("Off", s.readerPinyinMode == PinyinMode.OFF, { vm.setPinyinMode(PinyinMode.OFF) })
            }
            if (s.readerPinyinMode == PinyinMode.RARE) {
                Text("Outside the most common…", style = captionStyle(), modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                Chips {
                    listOf(500, 1000, 2000, 3000).forEach { n ->
                        PolyChip(if (n < 1000) "$n" else "${n / 1000}k", s.readerPinyinTop == n, { vm.setPinyinMode(PinyinMode.RARE, n) })
                    }
                }
            }
        }
        Gap(6.dp)
    }
}

@Composable
private fun Chips(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

@Composable
private fun Contents(titles: List<String>, current: Int, onPick: (Int) -> Unit) {
    val c = Diacritica.colors
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        SectionLabel("Contents", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp), state = list) {
            itemsIndexed(titles) { i, t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(i) }.background(if (i == current) c.accentSoft else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("${i + 1}", style = captionStyle(), modifier = Modifier.width(34.dp))
                    Text(t, style = bodyStyle(if (i == current) c.accent else c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Gap(12.dp)
    }
}

@Composable
private fun SearchSheet(vm: BooksViewModel, results: List<app.diacritica.mybooks.BookSearchHit>?, titles: List<String>, onPick: (Int, Int) -> Unit) {
    val c = Diacritica.colors
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        SectionLabel("Search in this book")
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.field)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (q.isEmpty()) Text("A word or phrase", style = bodyStyle(c.inkMuted))
            BasicTextField(
                q, { q = it }, singleLine = true, textStyle = bodyStyle(), cursorBrush = SolidColor(c.accent),
                visualTransformation = hanTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Gap(8.dp)
        PolyButton("Search", onClick = { vm.search(q) }, enabled = q.isNotBlank())
        Gap(8.dp)
        when {
            results == null -> {}
            results.isEmpty() -> Text("Not found in this book.", style = captionStyle())
            else -> {
                Text(if (results.size >= 100) "First 100 matches" else "${results.size} " + if (results.size == 1) "match" else "matches", style = captionStyle())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(results) { _, h ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(h.chapter, h.idx) }.padding(vertical = 10.dp)) {
                            Text(titles.getOrNull(h.chapter) ?: "Chapter ${h.chapter + 1}", style = captionStyle(c.accent), maxLines = 1)
                            Text(snippet(h.text, q), style = bodyStyle(size = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Gap(16.dp)
    }
}

/** A window of [text] around the first match of [q]. */
private fun snippet(text: String, q: String): String {
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 0) return text
    val from = (i - 50).coerceAtLeast(0)
    return (if (from > 0) "…" else "") + text.substring(from).replace('\n', ' ')
}

@Composable
private fun WordSheet(vm: BooksViewModel, w: BookWord, onOpenWord: (Lang, String) -> Unit) {
    val c = Diacritica.colors
    val uri = LocalUriHandler.current
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val r = w.result
    val hw: WordInfo = r?.headword ?: WordInfo(w.lang, w.token)
    val found = r != null && !r.isEmpty
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val head = hw.article?.let { a -> if (a.endsWith("'")) a + hw.word else "$a ${hw.word}" } ?: hw.word
                Text(head, style = headwordStyle(Diacritica.type.headwordLarge))
                val sub = if (w.lang == Lang.ZH) listOfNotNull(hw.pinyin, hw.traditional?.takeIf { it != hw.word }, hw.hsk?.let { ZhRanking.hskLabel(it) })
                else listOfNotNull(hw.pos, hw.gender, hw.plural?.let { "pl. $it" }, hw.ipa?.let { "/$it/" })
                if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), style = captionStyle(if (w.lang == Lang.ZH) c.accent else c.inkMuted))
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
        val forms = r?.forms.orEmpty()
        if (forms.isNotEmpty()) {
            FormNotes(w.token, forms, onLemma = { l -> vm.lookUp(l, w.sentence) }, modifier = Modifier.padding(top = 4.dp))
        } else if (found && !hw.word.equals(w.token, ignoreCase = true)) {
            Text("“${w.token}” is a form of ${hw.word}", style = captionStyle(c.ink), modifier = Modifier.padding(top = 4.dp))
        }
        app.diacritica.ui.components.VerbPatternNotes(hw, max = 4)
        hw.gloss?.let { Text(it, style = bodyStyle(), modifier = Modifier.padding(top = 8.dp)) }
        when {
            w.loading -> { Gap(14.dp); Text("Looking up…", style = captionStyle()) }
            w.fetching -> { Gap(14.dp); Text("Not in the offline dictionary. Downloading it…", style = captionStyle()) }
            !found -> { Gap(14.dp); Text("Not in the offline dictionary.", style = captionStyle()) }
            else -> {
                val concept = r!!.concepts.getOrNull(r.selected)
                Gap(14.dp)
                SectionLabel(concept?.label?.let { "Meaning · $it" } ?: "Translations")
                Gap(6.dp)
                r.results.filter { it.lang != w.lang }.sortedBy { if (it.lang == Lang.EN) 0 else 1 }.forEach { lr ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(lr.lang) }
                        val words = lr.words.take(3).joinToString(", ") { x ->
                            val base = x.article?.let { a -> if (a.endsWith("'")) a + x.word else "$a ${x.word}" } ?: x.word
                            if (x.lang == Lang.ZH && x.pinyin != null) "$base ${x.pinyin}" else base
                        }
                        Text(words.ifEmpty { "—" }, style = bodyStyle(if (words.isEmpty()) c.inkMuted else c.ink), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (w.sentence.isNotEmpty()) {
            Gap(14.dp); Hairline(); Gap(10.dp)
            Text(w.sentence, style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = if (w.lang == Lang.ZH) FontStyle.Normal else FontStyle.Italic))
        }
        Gap(16.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (found) PolyButton("Open in Dictionary", onClick = { onOpenWord(hw.lang, hw.word) })
            PolyButton(
                "${OfficialDictionary.name(w.lang)} ↗",
                onClick = { runCatching { uri.openUri(OfficialDictionary.url(w.lang, hw.word, hw.traditional)) } },
                primary = false,
            )
        }
    }
}
