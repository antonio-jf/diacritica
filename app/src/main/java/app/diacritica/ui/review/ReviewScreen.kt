package app.diacritica.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.data.user.CardKind
import app.diacritica.review.Fsrs
import app.diacritica.review.ReviewItem
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
import app.diacritica.ui.dictionary.ExampleLine
import app.diacritica.ui.dictionary.PinyinLine
import app.diacritica.ui.dictionary.WordTitle
import app.diacritica.ui.dictionary.grammarLine
import app.diacritica.ui.theme.Diacritica

@Composable
fun ReviewScreen(vm: ReviewViewModel) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val due by vm.dueCount.collectAsStateWithLifecycle()
    val new by vm.newCount.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val nextDue by vm.nextDue.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Review")
        Row(Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Due", due)
            Stat("New", new)
            Stat("Cards", total)
            if (state.doneThisSession > 0) Stat("Done", state.doneThisSession)
        }
        Gap(8.dp)
        val item = state.item
        when {
            state.loading -> Text("Loading…", style = captionStyle(), modifier = Modifier.padding(20.dp))
            item == null -> EmptyState(total, nextDue)
            else -> {
                ReviewCard(item, state, onReveal = vm::reveal, onPick = vm::pickArticle, onSpeak = vm::speak)
                Gap(12.dp)
                if (state.revealed) RatingRow(state.previews, suggestAgain = item.kind == CardKind.GENDER && state.pickedArticle != item.word?.article, onRate = vm::rate)
                else if (item.kind != CardKind.GENDER) {
                    PolyButton("Show answer", vm::reveal, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                }
                Text(
                    "${state.remaining} left in this session",
                    style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text("$value", style = headwordStyle(Diacritica.type.headword))
        SectionLabel(label)
    }
}

@Composable
private fun EmptyState(total: Int, nextDue: Long?) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (total == 0) {
            Text("No cards yet.", style = bodyStyle())
            Text("Tap ☆ next to a word in the Dictionary. Each saved word becomes a few cards: meaning, recall, listening, and article or pinyin practice.", style = captionStyle())
        } else {
            Text("All done for now.", style = bodyStyle())
            nextDue?.let { Text("Next card in ${Fsrs.formatInterval((it - System.currentTimeMillis()).coerceAtLeast(60_000))}.", style = captionStyle()) }
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    state: ReviewUiState,
    onReveal: () -> Unit,
    onPick: (String) -> Unit,
    onSpeak: () -> Unit,
) {
    LaunchedEffect(item.card.id) { if (item.kind == CardKind.LISTENING) onSpeak() }
    PolySurface(Modifier.padding(horizontal = 16.dp), padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("${item.kind.label} · ${item.lang.englishName}", Modifier.weight(1f))
            if (item.kind != CardKind.SENTENCE) PolyIconButton(PolyIcons.Speaker, "Play pronunciation", onSpeak)
        }
        Gap(10.dp)
        Front(item, state, onPick)
        if (state.revealed) {
            Gap(14.dp); Hairline(); Gap(14.dp)
            Back(item, state)
        } else if (item.kind != CardKind.GENDER) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onReveal))
        }
    }
}

@Composable
private fun Front(item: ReviewItem, state: ReviewUiState, onPick: (String) -> Unit) {
    val w = item.word
    when (item.kind) {
        CardKind.RECOGNITION -> w?.let { WordTitle(it, large = true) }
        CardKind.PINYIN -> w?.let { WordTitle(it, large = true); Text("How is it pronounced?", style = captionStyle()) }
        CardKind.LISTENING -> Text("Listen, then recall the word and its meaning.", style = bodyStyle())
        CardKind.RECALL -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val en = item.translations.firstOrNull { it.lang == Lang.EN }?.word
            Text(listOfNotNull(en, item.meaning?.let { "($it)" }).joinToString(" ").ifBlank { "?" }, style = headwordStyle())
            Text("in ${item.lang.englishName}?", style = captionStyle())
        }
        CardKind.GENDER -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("___ ${w?.word ?: ""}", style = headwordStyle(Diacritica.type.headwordLarge))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                articleOptions(item.lang).forEach { a -> PolyChip(a, state.pickedArticle == a, { if (!state.revealed) onPick(a) }) }
            }
        }
        CardKind.SENTENCE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.card.front ?: "", style = headwordStyle())
            Text("Translate into ${item.lang.englishName}.", style = captionStyle())
        }
    }
}

private fun articleOptions(lang: Lang): List<String> = when (lang) {
    Lang.DE -> listOf("der", "die", "das")
    Lang.FR -> listOf("le", "la")
    Lang.IT -> listOf("il", "lo", "la")
    Lang.ES -> listOf("el", "la")
    else -> emptyList()
}

@Composable
private fun Back(item: ReviewItem, state: ReviewUiState) {
    val w = item.word
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (item.kind) {
            CardKind.SENTENCE -> Text(item.card.back ?: "", style = headwordStyle())
            CardKind.GENDER -> {
                val right = state.pickedArticle == w?.article
                Text(
                    if (right) "Correct" else "Not quite",
                    style = bodyStyle(if (right) Diacritica.colors.success else Diacritica.colors.error),
                )
                w?.let { WordTitle(it, large = true) }
                w?.let { grammarLine(it) }?.let { Text(it, style = captionStyle()) }
            }
            else -> {
                if (item.kind != CardKind.RECOGNITION && item.kind != CardKind.PINYIN) w?.let { WordTitle(it, large = true) }
                if (item.lang == Lang.ZH) w?.let { PinyinLine(it) }
                w?.let { grammarLine(it) }?.let { Text(it, style = captionStyle()) }
                item.meaning?.let { Text(it, style = bodyStyle()) }
                if (item.kind == CardKind.RECOGNITION || item.kind == CardKind.PINYIN || item.kind == CardKind.LISTENING) {
                    item.translations.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LangBadge(t.lang)
                            Column {
                                WordTitle(t)
                                if (t.lang == Lang.ZH) t.pinyin?.let { Text(it, style = captionStyle(Diacritica.colors.accent)) }
                            }
                        }
                    }
                }
                w?.let { ExampleLine(it) }
            }
        }
    }
}

@Composable
private fun RatingRow(previews: Map<Fsrs.Rating, String>, suggestAgain: Boolean, onRate: (Fsrs.Rating) -> Unit) {
    val c = Diacritica.colors
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Fsrs.Rating.entries.forEach { r ->
            val tint: Color = when (r) {
                Fsrs.Rating.AGAIN -> c.error
                Fsrs.Rating.HARD -> c.warning
                Fsrs.Rating.GOOD -> c.success
                Fsrs.Rating.EASY -> c.accent
            }
            val shape = RoundedCornerShape(Diacritica.shapes.button.coerceAtMost(14.dp))
            val emphasize = suggestAgain && r == Fsrs.Rating.AGAIN
            Column(
                Modifier.weight(1f).clip(shape)
                    .background(if (emphasize) tint.copy(alpha = 0.18f) else c.surfaceAlt)
                    .border(BorderStroke(if (emphasize) 2.dp else 1.dp, tint.copy(alpha = 0.6f)), shape)
                    .clickable(role = Role.Button) { onRate(r) }
                    .heightIn(min = 56.dp).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(r.label, style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint), textAlign = TextAlign.Center)
                Text(previews[r] ?: "", style = captionStyle(), textAlign = TextAlign.Center)
            }
        }
    }
}
