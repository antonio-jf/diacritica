@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.mybooks.BookNote
import app.diacritica.mybooks.Breakdown
import app.diacritica.mybooks.GrammarPoint
import app.diacritica.settings.AppSettings
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import java.text.BreakIterator

// ------------------------------------------------------------------ ranges in a paragraph

/** The word around [offset] as a range, for extending a selection word by word. */
internal fun wordRange(text: String, offset: Int): IntRange? {
    if (text.isEmpty()) return null
    fun isWordChar(ch: Char) = ch.isLetterOrDigit() || ch == '\'' || ch == '’' || ch == '-'
    var i = offset.coerceIn(0, text.length - 1)
    if (!isWordChar(text[i]) && i > 0 && isWordChar(text[i - 1])) i -= 1
    if (!isWordChar(text[i])) return i..i
    var s = i
    var e = i + 1
    while (s > 0 && isWordChar(text[s - 1])) s--
    while (e < text.length && isWordChar(text[e])) e++
    return s until e
}

/** The sentence around [offset] as a range (without surrounding spaces). */
internal fun sentenceRange(text: String, offset: Int, lang: Lang): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val it = BreakIterator.getSentenceInstance(lang.locale)
    it.setText(text)
    val o = offset.coerceIn(0, text.length - 1)
    var end = it.following(o).let { e -> if (e == BreakIterator.DONE) text.length else e }
    var start = it.previous().let { s -> if (s == BreakIterator.DONE) 0 else s }
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    return start until end
}

// ------------------------------------------------------------------ selection bar

/** Shown instead of the progress line while a passage is selected. */
@Composable
internal fun SelectionBar(vm: BooksViewModel, text: String?, onRehearse: (String) -> Unit) {
    val c = Diacritica.colors
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text?.replace('\n', ' ')?.let { "“$it”" } ?: "",
                style = bodyStyle(size = 14.sp).copy(fontStyle = FontStyle.Italic), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PolyIconButton(PolyIcons.Close, "Clear selection", vm::clearSelection, tint = c.inkMuted)
        }
        Text("Tap words to extend or trim the selection", style = captionStyle())
        Gap(8.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PolyButton("Break down", onClick = { vm.openPanel(breakdown = true) })
            PolyButton("Translate", onClick = { vm.openPanel(breakdown = false) }, primary = false)
            PolyButton("Rehearse this", onClick = { text?.let(onRehearse) }, primary = false)
            PolyButton("Highlight", onClick = vm::highlightSelection, primary = false)
            PolyButton("Copy", onClick = { text?.let { clipboard.setText(AnnotatedString(it)) }; vm.clearSelection() }, primary = false)
        }
    }
}

// ------------------------------------------------------------------ passage panel

@Composable
internal fun PassageSheet(
    vm: BooksViewModel, p: PassagePanel, settings: AppSettings, bookLang: Lang,
    onRehearse: (String, String?) -> Unit, onGoTo: (Int, Int) -> Unit,
) {
    val c = Diacritica.colors
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(p.message) { if (p.message != null) { kotlinx.coroutines.delay(2500); vm.clearPanelMessage() } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
        Text(p.passage, style = bodyStyle(size = 17.sp).copy(fontFamily = app.diacritica.ui.theme.Fonts.sourceSerif))
        Gap(10.dp)
        Row {
            PolyChip("Break down", p.breakdownMode, { vm.showBreakdown(true) })
            Spacer(Modifier.width(8.dp))
            PolyChip("Translate", !p.breakdownMode, { vm.showBreakdown(false) })
        }
        Gap(12.dp)
        p.error?.let { Text(it, style = bodyStyle(c.warning)); Gap(8.dp) }
        if (p.breakdownMode) BreakdownView(vm, p, bookLang) else TranslationView(vm, p, settings, bookLang)
        p.message?.let { Gap(10.dp); Text(it, style = captionStyle(c.accent)) }
        Gap(16.dp)
        Hairline()
        Gap(12.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PolyButton("Rehearse this", onClick = { onRehearse(p.passage, p.breakdown?.translation) })
            PolyButton(if (p.highlighted) "Remove highlight" else "Highlight", onClick = { vm.setHighlight(!p.highlighted) }, primary = false)
            PolyButton("Go to passage", onClick = { onGoTo(p.chapter, p.paragraph) }, primary = false)
            PolyButton("Copy", onClick = { clipboard.setText(AnnotatedString(p.passage)) }, primary = false)
        }
    }
}

@Composable
private fun TranslationView(vm: BooksViewModel, p: PassagePanel, settings: AppSettings, bookLang: Lang) {
    val c = Diacritica.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        settings.activeLanguages.filter { it != bookLang }.forEach { l -> PolyChip(l.short, l == p.target, { vm.translate(l) }) }
    }
    Gap(10.dp)
    when {
        p.translating -> { Text("Translating on your phone…", style = captionStyle()); Gap(6.dp); LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line) }
        p.translation != null -> {
            Text(p.translation, style = bodyStyle())
            Gap(6.dp)
            Text("On-device translation. Break down gives a fuller explanation.", style = captionStyle())
        }
    }
}

@Composable
private fun BreakdownView(vm: BooksViewModel, p: PassagePanel, bookLang: Lang) {
    val c = Diacritica.colors
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val b: Breakdown = p.breakdown ?: run {
        if (p.loading) {
            Text("Gemini is breaking it down…", style = captionStyle())
            Gap(6.dp)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
        } else if (p.error != null) PolyButton("Try again", onClick = vm::breakDown)
        return
    }
    SectionLabel("Translation")
    Gap(4.dp)
    Text(b.translation, style = bodyStyle())
    b.literal?.let { Gap(4.dp); Text("Literally: $it", style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = FontStyle.Italic)) }

    if (b.words.isNotEmpty()) {
        Gap(16.dp)
        SectionLabel("Word by word")
        Gap(6.dp)
        b.words.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(110.dp)) {
                    Text(w.text, style = bodyStyle(size = 16.sp).copy(fontWeight = FontWeight.SemiBold))
                    w.reading?.let { Text(it, style = captionStyle(c.accent)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(w.meaning + (w.lemma?.let { "  ($it)" } ?: ""), style = bodyStyle(size = 15.sp))
                    w.role?.let { Text(it, style = captionStyle()) }
                }
            }
        }
    }

    if (b.grammar.isNotEmpty()) {
        Gap(16.dp)
        SectionLabel("Grammar")
        Gap(6.dp)
        b.grammar.forEach { g -> GrammarCard(g, made = g.title in p.lessons, onLesson = { vm.makeLesson(g) }); Gap(10.dp) }
    }

    if (b.vocabulary.isNotEmpty()) {
        Gap(6.dp)
        SectionLabel("Worth learning")
        Gap(4.dp)
        b.vocabulary.forEach { v ->
            val isSaved = "${bookLang.code}:${v.word}" in saved
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.word + (v.reading?.let { "  $it" } ?: ""), style = bodyStyle(size = 16.sp).copy(fontWeight = FontWeight.SemiBold))
                    Text(v.meaning, style = bodyStyle(size = 14.sp))
                    v.note?.let { Text(it, style = captionStyle()) }
                }
                PolyIconButton(
                    if (isSaved) PolyIcons.StarFilled else PolyIcons.Star, if (isSaved) "Remove from saved" else "Save word",
                    onClick = { vm.saveVocab(v) }, tint = if (isSaved) c.savedStar else c.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun GrammarCard(g: GrammarPoint, made: Boolean, onLesson: () -> Unit) {
    val c = Diacritica.colors
    PolySurface {
        Text(g.title, style = headwordStyle(17.sp))
        if (g.pattern.isNotBlank()) { Gap(2.dp); Text(g.pattern, style = captionStyle(c.accent)) }
        if (g.inPassage.isNotBlank()) { Gap(4.dp); Text("In the passage: ${g.inPassage}", style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = FontStyle.Italic)) }
        Gap(6.dp)
        Text(g.explanation, style = bodyStyle(size = 15.sp))
        g.examples.forEach { e ->
            Gap(6.dp)
            Text(e.text, style = bodyStyle(size = 15.sp))
            if (e.translation.isNotBlank()) Text(e.translation, style = captionStyle())
        }
        Gap(10.dp)
        if (made) Text("✓ In Grammar › Lessons" + if (g.practice.isNotEmpty()) " (with ${g.practice.size} practice sentences)" else "", style = captionStyle(c.success))
        else PolyButton("Make a lesson", onClick = onLesson, primary = false)
    }
}

// ------------------------------------------------------------------ notes

@Composable
internal fun NotesSheet(
    notes: List<BookNote>, marks: List<app.diacritica.mybooks.BookMark>, chapterTitles: List<String>,
    onOpen: (BookNote) -> Unit, onDelete: (Long) -> Unit,
    onOpenMark: (app.diacritica.mybooks.BookMark) -> Unit, onDeleteMark: (Long) -> Unit,
) {
    val c = Diacritica.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (notes.isEmpty() && marks.isEmpty()) {
            SectionLabel("Notes and bookmarks", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text(
                "Long-press a sentence to select it, then Break down, Translate or Highlight. The bookmark button at the top marks " +
                    "your place. Both appear here.",
                style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            if (marks.isNotEmpty()) {
                item { SectionLabel("Bookmarks", Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                items(marks, key = { "m${it.id}" }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenMark(m) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(chapterTitles.getOrNull(m.chapter) ?: "Chapter ${m.chapter + 1}", style = captionStyle(c.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.label, style = bodyStyle(size = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        PolyIconButton(PolyIcons.Trash, "Delete bookmark", { onDeleteMark(m.id) }, tint = c.inkMuted)
                    }
                }
            }
            if (notes.isNotEmpty()) item { SectionLabel("Notes", Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            items(notes, key = { "n${it.id}" }) { n ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(n) }.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.passage.replace('\n', ' '), style = bodyStyle(size = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val tags = listOfNotNull(
                            chapterTitles.getOrNull(n.chapter),
                            "highlight".takeIf { n.highlight },
                            "breakdown".takeIf { n.breakdown != null },
                            "translation".takeIf { n.translation != null && n.breakdown == null },
                            n.lessons?.let { "lessons" },
                        )
                        Text(tags.joinToString(" · "), style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PolyIconButton(PolyIcons.Trash, "Delete note", { onDelete(n.id) }, tint = c.inkMuted)
                }
            }
        }
        Gap(12.dp)
    }
}

/** Background of highlighted passages (a soft marker yellow, apart from selection and word shading). */
@Composable
internal fun highlightColor(): androidx.compose.ui.graphics.Color = Diacritica.colors.savedStar.copy(alpha = 0.26f)

/** Background of selected text. */
@Composable
internal fun selectionColor(): androidx.compose.ui.graphics.Color = Diacritica.colors.accent.copy(alpha = 0.18f)
