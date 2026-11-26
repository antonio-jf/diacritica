@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.grammar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang
import app.diacritica.tests.Lesson
import app.diacritica.tests.LessonExample
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.InlineMarkdown
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.theme.Diacritica

/** Two tabs at the top of Grammar: an underlined label for the selected one. */
@Composable
internal fun GrammarTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        tabs.forEachIndexed { i, t ->
            Column(Modifier.clickable { onSelect(i) }.padding(vertical = 6.dp)) {
                Text(t, style = bodyStyle(if (i == selected) c.ink else c.inkMuted, 15.sp).copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal))
                Gap(4.dp)
                Box(Modifier.height(2.dp).width(28.dp).clip(RoundedCornerShape(1.dp)).background(if (i == selected) c.accent else c.ground))
            }
        }
    }
}

/** One full lesson in the Full lessons list: title and the start of its overview. */
@Composable
internal fun FullLessonRow(l: Lesson, onOpen: () -> Unit) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        l.lang?.let { LangBadge(it); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(l.title, style = titleStyle().copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(InlineMarkdown.render(l.explanation).text, style = captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = titleStyle().copy(fontSize = 20.sp, color = c.inkMuted), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun LessonExampleRow(e: LessonExample, lang: Lang?, onSpeak: (Lang, String) -> Unit) {
    val c = Diacritica.colors
    val style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 16.sp, color = c.ink, lineHeight = 22.sp)
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(InlineMarkdown.render(e.text, c.ink), style = style)
            if (e.reading.isNotBlank()) Text(e.reading, style = captionStyle(c.accent))
            if (e.translation.isNotBlank()) Text(e.translation, style = captionStyle())
            if (e.note.isNotBlank()) Text(InlineMarkdown.render(e.note, c.inkMuted), style = bodyStyle(c.inkMuted, 13.sp).copy(fontStyle = FontStyle.Italic))
        }
        lang?.let { l -> PolyIconButton(PolyIcons.Speaker, "Play", { onSpeak(l, InlineMarkdown.render(e.text).text) }) }
    }
}

/** A full lesson as its own page. */
@Composable
internal fun FullLessonPage(l: Lesson, onBack: () -> Unit, onSpeak: (Lang, String) -> Unit, onPractise: (Lesson) -> Unit) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    Column(Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp))
            Text("Full lesson", style = captionStyle())
        }
        // Select part of the lesson and tap Ask to ask about it.
        app.diacritica.ui.chat.Askable("the lesson “${l.title}”") {
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                l.lang?.let { LangBadge(it); Spacer(Modifier.width(10.dp)) }
                Text(l.title, style = titleStyle().copy(fontSize = 22.sp), modifier = Modifier.weight(1f))
            }
            if (l.why.isNotBlank()) Text(l.why, style = captionStyle(c.accent))
            Text(InlineMarkdown.render(l.explanation, c.ink), style = bodyStyle(size = 16.sp))
            if (l.pattern.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SectionLabel("Form")
                    l.pattern.lines().filter { it.isNotBlank() }.forEach {
                        Text(InlineMarkdown.render(it.trim(), c.ink), style = bodyStyle(size = 15.sp).copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
            l.sections.forEach { sec ->
                Gap(8.dp)
                Text(sec.heading, style = titleStyle().copy(fontSize = 18.sp))
                Text(InlineMarkdown.render(sec.text, c.ink), style = bodyStyle(size = 15.sp))
                sec.examples.forEach { LessonExampleRow(it, l.lang, onSpeak) }
            }
            if (l.examples.isNotEmpty()) {
                Gap(8.dp)
                SectionLabel(if (l.sections.isEmpty()) "Examples" else "More examples")
                l.examples.forEach { LessonExampleRow(it, l.lang, onSpeak) }
            }
            if (l.pitfalls.isNotEmpty()) {
                Gap(8.dp)
                SectionLabel("Common mistakes")
                l.pitfalls.forEach { p ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = c.error, textDecoration = TextDecoration.LineThrough)) { append(InlineMarkdown.render(p.wrong).text) }
                            append("  →  ")
                            withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.SemiBold)) { append(InlineMarkdown.render(p.right).text) }
                        }, style = bodyStyle(size = 15.sp))
                        if (p.note.isNotBlank()) Text(InlineMarkdown.render(p.note, c.ink), style = bodyStyle(size = 14.sp))
                    }
                }
            }
            if (l.contrast.isNotBlank()) {
                Gap(8.dp)
                SectionLabel("Compared with English and Spanish")
                Text(InlineMarkdown.render(l.contrast, c.ink), style = bodyStyle(size = 15.sp))
            }
            if (l.tip.isNotBlank()) {
                Gap(4.dp)
                Text(InlineMarkdown.render("**Tip:** " + l.tip, c.ink), style = bodyStyle(size = 15.sp))
            }
            if (l.practice.isNotEmpty()) {
                Gap(8.dp)
                SectionLabel("Try these")
                l.practice.forEach { p -> Text("• $p", style = bodyStyle(size = 14.sp)) }
                Gap(4.dp)
                PolyButton("Practise as a mini test", { onPractise(l) })
            }
        }
        }
    }
}

/** "Ask for a lesson": describe a word, a character or a structure; Gemini writes a full lesson on it. */
@Composable
internal fun AskLessonCard(langs: List<Lang>, initial: Lang?, library: app.diacritica.grammar.LessonLibrary) {
    val c = Diacritica.colors
    val asking by library.asking.collectAsState()
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var lang by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf((initial ?: langs.firstOrNull { it != Lang.EN && it != Lang.ES } ?: langs.firstOrNull() ?: Lang.EN).code) }
    val chosen = Lang.fromCode(lang) ?: Lang.EN
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ask for a lesson", style = titleStyle().copy(fontSize = 16.sp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            langs.forEach { l -> app.diacritica.ui.components.PolyChip(l.nativeName, l == chosen, { lang = l.code }) }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.field)).background(c.ground)
                .border(1.dp, c.line, RoundedCornerShape(Diacritica.shapes.field)).padding(12.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    when (chosen) {
                        Lang.ZH -> "A word, a character or a structure… e.g. 起 and the words built with it, or when to use 把"
                        Lang.DE -> "A word or a structure… e.g. the difference between seit and vor, or verbs with zu"
                        Lang.FR -> "A word or a structure… e.g. when to use the subjunctive, or depuis vs pendant"
                        Lang.IT -> "A word or a structure… e.g. ci and ne, or the passato remoto"
                        else -> "A word, an expression or a structure you want explained"
                    },
                    style = bodyStyle(c.inkMuted, 15.sp),
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text, onValueChange = { text = it.take(400) },
                textStyle = bodyStyle(size = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyButton(
                if (asking.busy) "Writing the lesson…" else "Write lesson",
                { library.ask(text, chosen) },
                enabled = !asking.busy && text.isNotBlank(),
            )
            Spacer(Modifier.width(10.dp))
            if (!asking.busy) Text("Saved here when it's ready", style = captionStyle(), modifier = Modifier.weight(1f))
        }
        asking.error?.let { Text(it, style = captionStyle(c.error)) }
        // Clear the box once the lesson is written.
        androidx.compose.runtime.LaunchedEffect(asking.done) { if (asking.done != null) text = "" }
    }
}
