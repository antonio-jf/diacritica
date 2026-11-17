@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.tests

import app.diacritica.ui.theme.hanTransformation
import app.diacritica.ui.theme.roomyForHan
import app.diacritica.ui.components.textScaled
import app.diacritica.tests.TestRequest
import app.diacritica.tests.TestGenerator
import app.diacritica.tests.SentenceLength
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.rehearse.RuleChecker
import app.diacritica.rehearse.Verdict
import app.diacritica.settings.AppSettings
import app.diacritica.tests.AnswerReview
import app.diacritica.tests.AttemptStatus
import app.diacritica.tests.Lesson
import app.diacritica.tests.TestAttempt
import app.diacritica.tests.TestScoring
import app.diacritica.tests.TestSet
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.HandwritingPad
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.InlineMarkdown
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
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.rehearse.diffText
import app.diacritica.ui.theme.Diacritica
import java.text.DateFormat
import java.util.Date

/**
 * Tests: translate a set of sentences into your languages, send them for review, get a score,
 * corrections and grammar lessons aimed at your mistakes.
 */
@Composable
fun TestsScreen(vm: TestsViewModel, settings: AppSettings, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenGrammar: () -> Unit = {}) {
    val st by vm.state.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    when (val p = st.page) {
        TestsPage.List -> TestList(vm, settings, attempts, onBack, onOpenGrammar)
        is TestsPage.Take -> {
            BackHandler { vm.backToList() }
            val a = attempts.firstOrNull { it.id == p.attemptId }
            val t = a?.let { vm.testFor(it) }
            if (a == null || t == null) LaunchedEffect(Unit) { vm.backToList() } else TakeTest(vm, a, t, st.position)
        }
        is TestsPage.Result -> {
            BackHandler { vm.backToList() }
            val a = attempts.firstOrNull { it.id == p.attemptId }
            val t = a?.let { vm.testFor(it) }
            if (a == null || t == null) LaunchedEffect(Unit) { vm.backToList() } else ResultView(vm, a, t, st.reviewing, onOpenSettings)
        }
    }
}

// ================================================================== list

@Composable
private fun TestList(vm: TestsViewModel, settings: AppSettings, attempts: List<TestAttempt>, onBack: () -> Unit, onOpenGrammar: () -> Unit) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Tests", Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Translate 8 sentences into your languages and send them for review. You get a score, every answer corrected, " +
                    "and short grammar lessons aimed at what you got wrong. Reviews are written by Gemini (your key).",
                style = bodyStyle(c.inkMuted, 14.sp),
            )
            if (attempts.any { it.status == AttemptStatus.REVIEWED }) {
                Text(
                    "Your weak points and all your lessons ›",
                    style = captionStyle(c.accent),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpenGrammar).padding(vertical = 6.dp),
                )
            }
            val open1 = attempts.filter { it.status != AttemptStatus.REVIEWED }
            if (open1.isNotEmpty()) {
                SectionLabel("Unfinished")
                open1.forEach { a -> AttemptRow(vm, a) { vm.open(a) } }
            }
            GenerateCard(vm, settings)
            val mine by vm.generated.collectAsStateWithLifecycle()
            if (mine.isNotEmpty()) {
                SectionLabel("Your tests")
                mine.forEach { t ->
                    val best = attempts.filter { it.testId == t.id && it.review != null }.maxOfOrNull { it.review!!.score }
                    TestCard(vm, t, settings, best, expanded = open == t.id, onToggle = { open = if (open == t.id) null else t.id }, onDelete = { vm.deleteGenerated(t) })
                }
            }
            SectionLabel("Tests")
            vm.tests.forEach { t ->
                val best = attempts.filter { it.testId == t.id && it.review != null }.maxOfOrNull { it.review!!.score }
                TestCard(vm, t, settings, best, expanded = open == t.id, onToggle = { open = if (open == t.id) null else t.id })
            }
            val done = attempts.filter { it.status == AttemptStatus.REVIEWED }
            if (done.isNotEmpty()) {
                SectionLabel("History")
                done.forEach { a -> AttemptRow(vm, a) { vm.open(a) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestCard(vm: TestsViewModel, t: TestSet, settings: AppSettings, best: Int?, expanded: Boolean, onToggle: () -> Unit, onDelete: (() -> Unit)? = null) {
    val c = Diacritica.colors
    PolySurface(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = titleStyle().copy(fontSize = 18.sp))
                Text("${t.level} · HSK ${t.hsk} · ${t.items.size} sentences", style = captionStyle(c.accent))
                Gap(4.dp)
                Text(t.description, style = bodyStyle(size = 14.sp))
            }
            best?.let { ScoreBadge(it, Modifier.padding(start = 8.dp)) }
        }
        if (expanded) {
            var source by rememberSaveable(t.id) { mutableStateOf(Lang.EN) }
            val choices = settings.activeLanguages.filter { it != source }
            var picked by remember(t.id, source) { mutableStateOf(vm.defaultTargets(settings.activeLanguages, source).map { it.code }) }
            Gap(10.dp)
            Hairline()
            Gap(10.dp)
            Text("Sentences in", style = captionStyle())
            Gap(4.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Lang.EN, Lang.ES).forEach { l -> PolyChip(l.nativeName, source == l, { source = l }) }
            }
            Gap(8.dp)
            Text("Translate into", style = captionStyle())
            Gap(4.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { l ->
                    val on = l.code in picked
                    PolyChip(l.nativeName, on, { picked = if (on) picked - l.code else picked + l.code })
                }
            }
            Gap(10.dp)
            val langs = choices.filter { it.code in picked }
            PolyButton(
                if (langs.isEmpty()) "Pick at least one language" else "Start · ${langs.size * t.items.size} answers",
                { vm.start(t, source, langs) }, enabled = langs.isNotEmpty(),
            )
            onDelete?.let { del ->
                Text("Delete this test", style = captionStyle(c.error),
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = del).padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun AttemptRow(vm: TestsViewModel, a: TestAttempt, onClick: () -> Unit) {
    val c = Diacritica.colors
    val items = vm.testFor(a)?.items?.size ?: 0
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.submittedAt ?: a.startedAt))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.title, style = bodyStyle(), maxLines = 1)
            val status = when (a.status) {
                AttemptStatus.DRAFT -> "In progress · ${a.answeredCount(items)} of ${items * a.langs.size} answered"
                AttemptStatus.PENDING -> "Waiting for review"
                AttemptStatus.REVIEWED -> "Reviewed"
            }
            Text("$date · ${a.langs.joinToString(" ") { it.code.uppercase() }} · $status", style = captionStyle(if (a.status == AttemptStatus.PENDING) c.warning else c.inkMuted))
        }
        a.review?.let { ScoreBadge(it.score) }
    }
}

@Composable
private fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    val col = scoreColor(score)
    Box(modifier.size(48.dp).clip(CircleShape).background(col.copy(alpha = 0.14f)).border(2.dp, col, CircleShape), contentAlignment = Alignment.Center) {
        Text("$score", style = bodyStyle(col, 16.sp).copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Diacritica.colors.success
    score >= 55 -> Diacritica.colors.warning
    else -> Diacritica.colors.error
}

// ================================================================== taking a test

@Composable
private fun TakeTest(vm: TestsViewModel, a: TestAttempt, t: TestSet, position: Int) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    val i = position.coerceIn(0, t.items.lastIndex)
    val item = t.items[i]
    var hint by remember(i) { mutableStateOf(false) }
    var padFor by remember(i) { mutableStateOf<Lang?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back to tests (your answers are saved)", vm::backToList, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader(t.title, Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Progress dots: tap to jump; filled = every language answered.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                t.items.indices.forEach { k ->
                    val full = a.langs.all { a.answer(k, it).isNotBlank() }
                    val some = a.langs.any { a.answer(k, it).isNotBlank() }
                    Box(
                        Modifier.size(if (k == i) 26.dp else 22.dp).clip(CircleShape)
                            .background(if (full) c.accent else if (some) c.accentSoft else c.surface)
                            .border(if (k == i) 2.dp else 1.dp, if (k == i) c.ink else c.line, CircleShape)
                            .clickable { vm.goTo(k) }.semantics { contentDescription = "Sentence ${k + 1}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${k + 1}", style = captionStyle(if (full) c.onAccent else c.ink).copy(fontSize = 11.sp))
                    }
                }
            }
            Text("Sentence ${i + 1} of ${t.items.size}", style = captionStyle())
            Text(item.prompt(a.source), style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 22.sp, lineHeight = 30.sp, color = c.ink))
            item.hint?.let { h ->
                if (hint) Text("Hint: $h", style = captionStyle(c.accent))
                else Text("Show a hint", style = captionStyle(c.accent), modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { hint = true }.padding(vertical = 4.dp))
            }
            a.langs.forEach { l ->
                AnswerField(
                    lang = l,
                    value = a.answer(i, l),
                    onChange = { vm.setAnswer(a.id, i, l, it) },
                    onHandwriting = if (l == Lang.ZH) ({ padFor = if (padFor == l) null else l }) else null,
                )
                if (padFor == l) {
                    HandwritingPad(
                        before = a.answer(i, l),
                        onInsert = { vm.setAnswer(a.id, i, l, a.answer(i, l) + it) },
                        onBackspace = { vm.setAnswer(a.id, i, l, a.answer(i, l).dropLast(1)) },
                        onClose = { padFor = null },
                    )
                }
            }
            Gap(4.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) PolyButton("Previous", { vm.goTo(i - 1) }, primary = false)
                Spacer(Modifier.weight(1f))
                if (i < t.items.lastIndex) PolyButton("Next", { vm.goTo(i + 1) })
            }
            val total = t.items.size * a.langs.size
            val answered = a.answeredCount(t.items.size)
            Hairline()
            Text(
                if (answered == total) "All $total answers written." else "$answered of $total answered. Empty answers score 0.",
                style = captionStyle(),
            )
            PolyButton("Send for review", { vm.submit(a.id) }, enabled = answered > 0, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AnswerField(lang: Lang, value: String, onChange: (String) -> Unit, onHandwriting: (() -> Unit)?) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.widthIn(min = 40.dp).padding(top = 12.dp, end = 10.dp)) { LangBadge(lang) }
        Box(
            Modifier.weight(1f).clip(shape).background(c.surface).border(1.dp, c.line, shape)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        ) {
            if (value.isEmpty()) Text("In ${lang.englishName}…", style = bodyStyle(c.inkMuted, 17.sp))
            BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 17.sp, color = c.ink, lineHeight = 24.sp).roomyForHan(),
                visualTransformation = hanTransformation(),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().padding(end = if (onHandwriting != null) 40.dp else 0.dp).heightIn(min = 28.dp)
                    .semantics { contentDescription = "Answer in ${lang.englishName}" },
            )
            onHandwriting?.let {
                PolyIconButton(PolyIcons.Handwriting, "Write by hand", it, tint = c.inkMuted, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

// ================================================================== result

@Composable
private fun ResultView(vm: TestsViewModel, a: TestAttempt, t: TestSet, reviewing: Boolean, onOpenSettings: () -> Unit) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    val r = a.review
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back to tests", vm::backToList, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader(t.title, Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r == null) {
                PolySurface(Modifier.fillMaxWidth()) {
                    if (reviewing) {
                        Text("Reviewing your answers…", style = titleStyle().copy(fontSize = 18.sp))
                        Gap(4.dp)
                        Text("This takes up to a minute: every answer is marked and the lessons are written for you.", style = captionStyle())
                    } else {
                        Text("Waiting for review", style = titleStyle().copy(fontSize = 18.sp))
                        Gap(4.dp)
                        a.error?.let { Text(it, style = bodyStyle(c.error, 14.sp)) }
                        Gap(10.dp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PolyButton("Send for review", { vm.submit(a.id) })
                            if (a.error?.contains("key") == true) PolyButton("Settings", onOpenSettings, primary = false)
                        }
                    }
                }
                AnswersOnly(a, t)
                return@Column
            }

            // ------------------------------------------------ score
            PolySurface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBadge(r.score)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(TestScoring.label(r.score), style = titleStyle().copy(fontSize = 20.sp))
                        Text("${r.score} / 100 · ${a.langs.size * t.items.size} answers", style = captionStyle())
                    }
                }
                if (a.langs.size > 1) {
                    Gap(12.dp)
                    a.langs.forEach { l -> LangScore(l, r.scoreFor(l)) }
                }
                if (r.summary.isNotBlank()) {
                    Gap(12.dp)
                    Text(InlineMarkdown.render(r.summary, c.ink), style = bodyStyle(size = 15.sp))
                }
            }

            // ------------------------------------------------ lessons
            if (r.lessons.isNotEmpty()) {
                SectionLabel(if (r.lessons.size == 1) "Your lesson" else "Your lessons")
                Text("They're also kept on the Grammar page (Home › Grammar).", style = captionStyle())
                r.lessons.forEach { lesson -> LessonCard(lesson, onSpeak = vm::speak, onPractise = { vm.practise(lesson, a) }) }
            }

            // ------------------------------------------------ answers
            SectionLabel("Your answers")
            t.items.forEachIndexed { i, item ->
                PolySurface(Modifier.fillMaxWidth()) {
                    Text("${i + 1}. ${item.prompt(a.source)}", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                    r.forItem(i).forEach { ar -> AnswerReviewView(ar) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PolyButton("Take it again", { vm.retake(a) })
                PolyButton("Delete", { vm.delete(a.id) }, primary = false)
            }
            r.model?.let { Text("Reviewed by $it.", style = captionStyle()) }
        }
    }
}

@Composable
private fun LangScore(lang: Lang, score: Int) {
    val c = Diacritica.colors
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(lang) }
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceAlt)) {
            Box(Modifier.fillMaxWidth(score / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(scoreColor(score)))
        }
        Text("$score", style = captionStyle(c.ink), modifier = Modifier.width(40.dp.textScaled()).padding(start = 10.dp))
    }
}

@Composable
internal fun LessonCard(
    lesson: Lesson,
    onSpeak: (Lang, String) -> Unit,
    onPractise: () -> Unit,
    source: String? = null,
    startOpen: Boolean = true,
) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    var open by rememberSaveable(lesson.title) { mutableStateOf(startOpen) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.accentSoft.copy(alpha = 0.45f)).border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            lesson.lang?.let { LangBadge(it); Spacer(Modifier.width(8.dp)) }
            Text(lesson.title, style = titleStyle().copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
            Icon(PolyIcons.ChevronDown, if (open) "Collapse" else "Expand", tint = c.inkMuted, modifier = Modifier.size(18.dp))
        }
        source?.let { Text(it, style = captionStyle()) }
        if (!open) return@Column
        if (lesson.why.isNotBlank()) Text(lesson.why, style = captionStyle(c.accent))
        Text(InlineMarkdown.render(lesson.explanation, c.ink), style = bodyStyle(size = 15.sp))
        if (lesson.examples.isNotEmpty()) {
            Gap(2.dp)
            lesson.examples.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(InlineMarkdown.render(e.text, c.ink), style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 16.sp, color = c.ink))
                        if (e.translation.isNotBlank()) Text(e.translation, style = captionStyle())
                    }
                    lesson.lang?.let { l -> PolyIconButton(PolyIcons.Speaker, "Play", { onSpeak(l, InlineMarkdown.render(e.text).text) }) }
                }
            }
        }
        if (lesson.practice.isNotEmpty()) {
            Gap(2.dp)
            SectionLabel("Try these")
            lesson.practice.forEach { p -> Text("• $p", style = bodyStyle(size = 14.sp)) }
            Gap(4.dp)
            PolyButton("Practise as a mini test", onPractise, primary = false)
        }
    }
}

@Composable
private fun AnswerReviewView(r: AnswerReview) {
    val c = Diacritica.colors
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(r.lang) }
            Text(
                when (r.verdict) { Verdict.CORRECT -> "Correct"; Verdict.ACCEPTABLE -> "Acceptable"; Verdict.INCORRECT -> "Incorrect"; Verdict.UNGRADED -> "Not marked" } + " · ${r.score}",
                style = captionStyle(scoreColor(r.score)),
            )
        }
        val answerStyle = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 17.sp, color = c.ink, lineHeight = 24.sp)
        val corrected = r.corrected?.let { InlineMarkdown.render(it).text }
        when {
            r.answer.isBlank() -> Text("No answer", style = captionStyle(c.error))
            corrected != null && (RuleChecker.loose(r.answer) != RuleChecker.loose(corrected) || r.answer.trim() != corrected.trim()) ->
                Text(diffText(r.answer, corrected), style = answerStyle)
            else -> Text(r.answer, style = answerStyle)
        }
        r.errors.forEach { e ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SectionLabel(e.type.replace('-', ' '), color = c.error)
                if (e.wrong.isNotBlank() || e.right.isNotBlank()) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = c.error, textDecoration = TextDecoration.LineThrough)) { append(InlineMarkdown.render(e.wrong).text) }
                        append("  →  ")
                        withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.SemiBold)) { append(InlineMarkdown.render(e.right).text) }
                    }, style = bodyStyle(size = 15.sp))
                }
                Text(InlineMarkdown.render(e.explanation, c.ink), style = bodyStyle(size = 14.sp))
            }
        }
        r.comment?.let { Text(InlineMarkdown.render(it, c.ink), style = captionStyle()) }
    }
}

/** A pending attempt: the answers as written. */
@Composable
private fun AnswersOnly(a: TestAttempt, t: TestSet) {
    SectionLabel("Your answers")
    t.items.forEachIndexed { i, item ->
        PolySurface(Modifier.fillMaxWidth()) {
            Text("${i + 1}. ${item.prompt(a.source)}", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
            a.langs.forEach { l ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(l) }
                    Text(a.answer(i, l).ifBlank { "—" }, style = bodyStyle(size = 15.sp))
                }
            }
        }
    }
}

/** "Write a new test": level, HSK, sentence length, how many, optional grammar focus and theme; Gemini writes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerateCard(vm: TestsViewModel, settings: AppSettings) {
    val c = Diacritica.colors
    val st by vm.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf("B2") }
    var hsk by rememberSaveable { mutableStateOf(4) }
    var length by rememberSaveable { mutableStateOf(SentenceLength.MEDIUM.name) }
    var count by rememberSaveable { mutableStateOf(8) }
    var focus by rememberSaveable { mutableStateOf("mixed") }
    var topic by rememberSaveable { mutableStateOf("") }
    PolySurface(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Write a new test", style = titleStyle().copy(fontSize = 18.sp))
                Text("Choose the level and sentence length; Gemini writes it.", style = captionStyle())
            }
            Icon(PolyIcons.ChevronDown, if (open) "Collapse" else "Expand", tint = c.inkMuted, modifier = Modifier.size(18.dp))
        }
        if (!open) return@PolySurface
        Gap(10.dp)
        Text("Level (French, German, Italian)", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TestGenerator.LEVELS.forEach { l -> PolyChip(l, level == l, { level = l }) }
        }
        Gap(8.dp)
        Text("Mandarin", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..6).forEach { h -> PolyChip("HSK $h", hsk == h, { hsk = h }) }
        }
        Gap(8.dp)
        Text("Sentence length", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SentenceLength.entries.forEach { l -> PolyChip("${l.label} · ${l.words.substringBefore(',')}", length == l.name, { length = l.name }) }
        }
        Gap(8.dp)
        Text("Sentences", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TestGenerator.COUNTS.forEach { n -> PolyChip("$n", count == n, { count = n }) }
        }
        Gap(8.dp)
        Text("Grammar focus", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            app.diacritica.rehearse.GrammarTopics.forLanguages(settings.activeLanguages).forEach { t ->
                PolyChip(t.label, focus == t.key, { focus = t.key })
            }
        }
        Gap(8.dp)
        val shape = RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(12.dp))
        BasicTextField(
            value = topic, onValueChange = { topic = it.take(80) }, singleLine = true,
            textStyle = bodyStyle(), cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(12.dp)) {
                    if (topic.isEmpty()) Text("Theme (optional): travel, work, family…", style = bodyStyle(c.inkMuted))
                    inner()
                }
            },
        )
        Gap(10.dp)
        PolyButton(
            if (st.generating) "Writing your test…" else "Write the test",
            { vm.generate(TestRequest(level, hsk, SentenceLength.valueOf(length), count, focus, topic)) },
            enabled = !st.generating,
        )
        st.generateError?.let { Text(it, style = bodyStyle(c.error, 14.sp), modifier = Modifier.padding(top = 6.dp)) }
    }
}
