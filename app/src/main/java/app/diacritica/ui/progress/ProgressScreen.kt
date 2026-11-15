package app.diacritica.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.Diacritica
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(vm: ProgressViewModel, onBack: () -> Unit) {
    val c = Diacritica.colors
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val pad = Diacritica.spacing.screen
    val nf = remember { NumberFormat.getIntegerInstance() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Progress", Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = pad, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressRange.entries.forEach { r -> PolyChip(r.label, st.range == r, { vm.setRange(r) }) }
        }
        if (st.loading) {
            Text("Counting…", style = captionStyle(), modifier = Modifier.padding(pad))
            return@Column
        }

        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Gap(4.dp)
            // ------------------------------------------------ Streaks
            PolySurface {
                SectionLabel("Streak")
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat(nf.format(st.streaks.current), if (st.streaks.current == 1) "day in a row" else "days in a row", Modifier.weight(1f))
                    BigStat(nf.format(st.streaks.longest), "longest streak", Modifier.weight(1f))
                    BigStat(nf.format(st.streaks.activeDays), "active days", Modifier.weight(1f))
                }
                Gap(12.dp)
                Heatmap(st.activity)
                Gap(4.dp)
                Text("Any Rehearse round, Review card, lookup or story opened counts for the day.", style = captionStyle())
            }

            // ------------------------------------------------ Rehearse accuracy
            PolySurface {
                SectionLabel("Rehearse · ${nf.format(st.rounds)} " + if (st.rounds == 1) "round" else "rounds")
                Gap(8.dp)
                if (st.accuracy.isEmpty()) {
                    Text("No rounds in this period yet.", style = captionStyle())
                } else {
                    st.accuracy.forEach { a ->
                        LangBar(
                            a.lang, fraction = (a.percent ?: 0) / 100f,
                            value = a.percent?.let { "$it%" } ?: "—",
                            detail = if (a.checked == 0) "${a.answered} answered, none checked" else "${a.right} of ${a.checked} right",
                            color = accuracyColor(a.percent),
                        )
                    }
                    Text("Right = correct or acceptable. Answers without a reference (not checked) don't count.", style = captionStyle())
                }
            }

            // ------------------------------------------------ Error types
            PolySurface {
                var filter by remember { mutableStateOf<Lang?>(null) }
                SectionLabel("Mistakes by type")
                Gap(8.dp)
                if (st.errors.isEmpty()) {
                    Text("No mistakes recorded in this period. Full explanations need Gemini; the offline check only notices accents and tones.", style = captionStyle())
                } else {
                    val langs = st.errors.flatMap { it.byLang.keys }.distinct().sortedBy { it.ordinal }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PolyChip("All", filter == null, { filter = null })
                        langs.forEach { l -> PolyChip(l.short, filter == l, { filter = l }) }
                    }
                    Gap(10.dp)
                    val rows = st.errors.map { e -> e.type to (filter?.let { e.byLang[it] ?: 0 } ?: e.count) }.filter { it.second > 0 }
                        .sortedByDescending { it.second }.take(10)
                    val max = rows.maxOfOrNull { it.second } ?: 1
                    rows.forEach { (type, n) -> TypeBar(type.replaceFirstChar { it.uppercase() }, n, n.toFloat() / max) }
                }
            }

            // ------------------------------------------------ Review
            PolySurface {
                SectionLabel("Review")
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat(nf.format(st.review.reviews), "cards reviewed", Modifier.weight(1f))
                    BigStat(st.review.recallPercent?.let { "$it%" } ?: "—", "remembered", Modifier.weight(1f))
                    BigStat(nf.format(st.matureCards), "mature cards", Modifier.weight(1f))
                }
                if (st.review.byLang.isNotEmpty()) {
                    Gap(10.dp)
                    st.review.byLang.entries.sortedBy { it.key.ordinal }.forEach { (l, p) ->
                        val (ok, all) = p
                        val pct = if (all == 0) 0 else ok * 100 / all
                        LangBar(l, pct / 100f, "$pct%", "$ok of $all", accuracyColor(pct))
                    }
                }
                Text("Remembered = any answer but Again. Mature = due again in 3 weeks or more.", style = captionStyle())
            }

            // ------------------------------------------------ Reading and words
            PolySurface {
                SectionLabel("Reading and words")
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat(nf.format(st.sentencesRead), "sentences read", Modifier.weight(1f))
                    BigStat(nf.format(st.lookups), "words looked up", Modifier.weight(1f))
                    BigStat(nf.format(st.savedWords), "saved words", Modifier.weight(1f))
                }
                if (st.wordsRead.isNotEmpty()) {
                    Gap(10.dp)
                    st.wordsRead.forEach { (l, n) ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(40.dp)) { LangBadge(l) }
                            Text(nf.format(n) + if (l == Lang.ZH) " characters read" else " words read", style = bodyStyle())
                        }
                    }
                }
                Text(
                    "${st.storiesOpened} " + (if (st.storiesOpened == 1) "story" else "stories") +
                        " opened. Words are counted up to where you are in each story, in your Reader languages.",
                    style = captionStyle(),
                )
            }
        }
    }
}

@Composable
private fun accuracyColor(percent: Int?): Color = when {
    percent == null -> Diacritica.colors.inkMuted
    percent >= 80 -> Diacritica.colors.success
    percent >= 50 -> Diacritica.colors.warning
    else -> Diacritica.colors.error
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = TextStyle(fontFamily = Diacritica.fonts.display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Diacritica.colors.ink))
        Text(label, style = captionStyle(), maxLines = 2)
    }
}

/** Language · bar · value, with a detail line. */
@Composable
private fun LangBar(lang: Lang, fraction: Float, value: String, detail: String, color: Color) {
    val c = Diacritica.colors
    Column(Modifier.padding(vertical = 4.dp).semantics { contentDescription = "${lang.englishName}: $value, $detail" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp)) { LangBadge(lang) }
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.line)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(color))
            }
            Text(value, style = bodyStyle(size = 14.sp).copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp).width(44.dp))
        }
        Text(detail, style = captionStyle(), modifier = Modifier.padding(start = 40.dp))
    }
}

@Composable
private fun TypeBar(label: String, count: Int, fraction: Float) {
    val c = Diacritica.colors
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = bodyStyle(size = 14.sp), modifier = Modifier.width(120.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.04f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(c.accent))
        }
        Text("$count", style = bodyStyle(size = 14.sp), modifier = Modifier.padding(start = 10.dp).width(32.dp))
    }
}

/** The last 16 weeks, one square per day (weeks are columns, Monday on top); darker = more done that day. */
@Composable
private fun Heatmap(activity: Map<LocalDate, Int>) {
    val c = Diacritica.colors
    val today = LocalDate.now()
    val weeks = 16
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    val active = activity.filterKeys { !it.isBefore(start) }.size
    Column(Modifier.semantics { contentDescription = "Activity calendar: $active active days in the last $weeks weeks" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Box(Modifier.size(width = 12.dp, height = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (d == DayOfWeek.MONDAY || d == DayOfWeek.THURSDAY || d == DayOfWeek.SUNDAY) {
                            Text(d.getDisplayName(DateTextStyle.NARROW, Locale.getDefault()), style = captionStyle().copy(fontSize = 9.sp))
                        }
                    }
                }
            }
            for (w in 0 until weeks) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (d in 0 until 7) {
                        val day = start.plusWeeks(w.toLong()).plusDays(d.toLong())
                        val n = activity[day] ?: 0
                        val color = when {
                            day.isAfter(today) -> Color.Transparent
                            n == 0 -> c.line
                            n <= 2 -> c.accent.copy(alpha = 0.35f)
                            n <= 6 -> c.accent.copy(alpha = 0.65f)
                            else -> c.accent
                        }
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Less", style = captionStyle().copy(fontSize = 10.sp))
            listOf(c.line, c.accent.copy(alpha = 0.35f), c.accent.copy(alpha = 0.65f), c.accent).forEach {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(it))
            }
            Text("More", style = captionStyle().copy(fontSize = 10.sp))
            Spacer(Modifier.weight(1f))
        }
    }
}
