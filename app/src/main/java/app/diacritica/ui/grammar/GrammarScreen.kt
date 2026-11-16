package app.diacritica.ui.grammar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.grammar.GrammarStats
import app.diacritica.grammar.LessonEntry
import app.diacritica.grammar.Trend
import app.diacritica.grammar.WeakPoint
import app.diacritica.rehearse.GrammarTopics
import app.diacritica.tests.Lesson
import app.diacritica.tests.TestSet
import app.diacritica.ui.components.Gap
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
import app.diacritica.ui.tests.LessonCard
import app.diacritica.ui.theme.Diacritica
import java.text.DateFormat
import java.util.Date

/**
 * Grammar: your recurring mistakes (from Rehearse and Tests) with how they're trending, every lesson
 * your test reviews wrote, and one-tap practice for each weak point.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrammarScreen(
    vm: GrammarViewModel,
    onBack: () -> Unit,
    onStartTest: (TestSet, Lang) -> Unit,
    onPractiseLesson: (Lesson, Long) -> Unit,
    onRehearse: (String) -> Unit,
    onOpenTests: () -> Unit,
) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Grammar", Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "What you keep getting wrong in Rehearse and Tests, the lessons your test reviews wrote, and practice aimed at each point.",
                style = bodyStyle(c.inkMuted, 14.sp),
            )
            if (st.loading) { Text("Looking through your answers…", style = captionStyle()); return@Column }

            if (st.langs.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PolyChip("All", st.filter == null, { vm.setFilter(null) })
                    st.langs.forEach { l -> PolyChip(l.nativeName, st.filter == l, { vm.setFilter(l) }) }
                }
            }
            st.message?.let { Text(it, style = bodyStyle(c.error, 14.sp)) }

            // ------------------------------------------------ weak points
            val weak = st.weakPoints.filter { st.filter == null || it.lang == st.filter }
            SectionLabel("Weak points")
            if (weak.isEmpty()) {
                PolySurface(Modifier.fillMaxWidth()) {
                    Text(
                        if (st.weakPoints.isEmpty()) "No patterns yet. Mistakes from Rehearse rounds checked by Gemini and from reviewed tests show up here once a kind of mistake happens more than once."
                        else "No weak points in this language.",
                        style = bodyStyle(size = 14.sp),
                    )
                    Gap(10.dp)
                    PolyButton("Take a test", onOpenTests, primary = false)
                }
            }
            weak.forEachIndexed { i, w ->
                WeakPointCard(
                    w, recommended = i < 3 && st.filter == null,
                    generating = st.generating == "${w.lang.code}:${w.type}",
                    busy = st.generating != null,
                    onPractise = { vm.practise(w, onStartTest) },
                    onRehearse = { onRehearse(w.topic) },
                )
            }

            // ------------------------------------------------ lessons
            val lessons = st.lessons.filter { st.filter == null || it.lesson.lang == st.filter }
            if (lessons.isNotEmpty()) {
                Gap(4.dp)
                SectionLabel("Your lessons · ${lessons.size}")
                lessons.forEach { e -> LessonRow(e, vm, onPractiseLesson) }
            }
        }
    }
}

@Composable
private fun LessonRow(e: LessonEntry, vm: GrammarViewModel, onPractiseLesson: (Lesson, Long) -> Unit) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.at))
    LessonCard(
        e.lesson, onSpeak = vm::speak, onPractise = { onPractiseLesson(e.lesson, e.attemptId) },
        source = "${e.testTitle} · $date" + if (e.times > 1) " · came up ${e.times} times" else "",
        startOpen = false,
    )
}

@Composable
private fun WeakPointCard(
    w: WeakPoint,
    recommended: Boolean,
    generating: Boolean,
    busy: Boolean,
    onPractise: () -> Unit,
    onRehearse: () -> Unit,
) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (recommended) c.accentSoft.copy(alpha = 0.45f) else c.surface)
            .border(1.dp, if (recommended) c.accent.copy(alpha = 0.5f) else c.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangBadge(w.lang)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(w.label, style = titleStyle().copy(fontSize = 17.sp))
                if (recommended) Text("Recommended next", style = captionStyle(c.accent))
            }
        }
        val (trendText, trendColor) = when (w.trend) {
            Trend.NEW -> "new in the last ${GrammarStats.WINDOW_DAYS} days" to c.warning
            Trend.UP -> "up from ${w.previous}" to c.error
            Trend.DOWN -> "down from ${w.previous}" to c.success
            Trend.SAME -> "same as before" to c.inkMuted
        }
        Text(
            buildAnnotatedString {
                append("${w.recent} in the last ${GrammarStats.WINDOW_DAYS} days · ")
                withStyle(SpanStyle(color = trendColor, fontWeight = FontWeight.SemiBold)) { append(trendText) }
                append(" · ${w.total} in total")
            },
            style = captionStyle(c.ink),
        )
        w.examples.firstOrNull()?.let { m ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.ground).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (m.wrong.isNotBlank() || m.right.isNotBlank()) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = c.error, textDecoration = TextDecoration.LineThrough)) { append(InlineMarkdown.render(m.wrong).text) }
                        append("  →  ")
                        withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.SemiBold)) { append(InlineMarkdown.render(m.right).text) }
                    }, style = bodyStyle(size = 15.sp))
                }
                if (m.explanation.isNotBlank()) Text(InlineMarkdown.render(m.explanation, c.ink), style = bodyStyle(size = 14.sp), maxLines = 4)
            }
        }
        if (w.examples.size > 1) {
            Text(
                "Also: " + w.examples.drop(1).joinToString("  ·  ") { "${InlineMarkdown.render(it.wrong).text} → ${InlineMarkdown.render(it.right).text}" },
                style = captionStyle(), maxLines = 2,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PolyButton(if (generating) "Writing a test…" else "Practice test", onPractise, enabled = !busy)
            val topic = GrammarTopics.byKey(w.topic)
            PolyButton(if (w.topic == "mixed") "Rehearse grammar" else "Rehearse: ${topic.label}", onRehearse, primary = false)
        }
    }
}
