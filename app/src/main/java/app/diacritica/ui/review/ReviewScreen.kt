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
fun ReviewScreen(vm: ReviewViewModel, onOpenWord: ((Lang, String) -> Unit)? = null) {
    LaunchedEffect(Unit) { vm.refresh() }
    app.diacritica.ui.components.WordLookupSheet(vm.lookup, onOpenDictionary = onOpenWord)
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
                ReviewCard(item, state, onReveal = vm::reveal, onPick = vm::pickArticle, onSpeak = vm::speak, onAnswer = vm::setAnswer)
                Gap(12.dp)
                if (state.revealed && item.kind != CardKind.SENTENCE) item.word?.let { w ->
                    Text(
                        "Word details ›", style = captionStyle(Diacritica.colors.accent),
                        modifier = Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(4.dp))
                            .clickable { vm.lookup.open(w.word, item.lang) }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                val typed = item.kind == CardKind.TRANSLATE || item.kind == CardKind.USE
                if (state.revealed) RatingRow(
                    state.previews,
                    suggested = if (item.kind == CardKind.GENDER && state.pickedArticle != item.word?.article) Fsrs.Rating.AGAIN else state.suggested,
                    onRate = vm::rate,
                )
                else if (typed) {
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PolyButton(
                            if (state.checking) "Checking…" else "Check", vm::checkSentence, modifier = Modifier.weight(1f),
                            enabled = state.answer.isNotBlank() && !state.checking && !state.taskLoading,
                        )
                        PolyButton("Show answer", vm::reveal, primary = false, modifier = Modifier.weight(1f))
                    }
                }
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
            Text("Tap ☆ next to a word in the Dictionary, Reader, Books or Vocabulary. Each saved word becomes a few cards: meaning, recall, listening, and article or pinyin practice. Rehearse adds sentence cards from your mistakes.", style = captionStyle())
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
    onAnswer: (String) -> Unit,
) {
    LaunchedEffect(item.card.id) { if (item.kind == CardKind.LISTENING) onSpeak() }
    val typed = item.kind == CardKind.TRANSLATE || item.kind == CardKind.USE
    PolySurface(Modifier.padding(horizontal = 16.dp), padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("${item.kind.label} · ${item.lang.englishName}", Modifier.weight(1f))
            if (item.kind != CardKind.SENTENCE) PolyIconButton(PolyIcons.Speaker, "Play pronunciation", onSpeak)
        }
        Gap(10.dp)
        Front(item, state, onPick)
        if (typed && !state.revealed) {
            Gap(12.dp)
            AnswerBox(state.answer, onAnswer, item.lang, if (item.kind == CardKind.USE) "Your sentence…" else "Your translation…")
        }
        if (state.revealed) {
            Gap(14.dp); Hairline(); Gap(14.dp)
            Back(item, state)
        } else if (item.kind != CardKind.GENDER && !typed) {
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
            app.diacritica.ui.components.TappableText(item.card.front ?: "", null, style = headwordStyle())
            Text("Translate into ${item.lang.englishName}.", style = captionStyle())
        }
        CardKind.TRANSLATE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val t = state.task
            when {
                state.taskLoading -> Text("Finding a sentence with this word…", style = captionStyle())
                t == null -> {
                    Text("No sentence with this word was found offline. Add a Gemini key to get one, or recall the word:", style = captionStyle())
                    w?.let { WordTitle(it, large = true) }
                }
                else -> {
                    app.diacritica.ui.components.TappableText(t.prompt, t.promptLang, style = headwordStyle(20.sp))
                    Text("Translate into ${item.lang.englishName} · ${t.promptLang.short} sentence, ${t.source}", style = captionStyle())
                }
            }
        }
        CardKind.USE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            w?.let { WordTitle(it, large = true) }
            if (item.lang == Lang.ZH) w?.let { PinyinLine(it) }
            val en = item.translations.firstOrNull { it.lang == Lang.EN }?.word
            listOfNotNull(en, item.meaning?.let { "($it)" }).joinToString(" ").ifBlank { null }?.let { Text(it, style = bodyStyle(Diacritica.colors.inkMuted)) }
            Text("Write a sentence in ${item.lang.englishName} that uses this word.", style = captionStyle())
        }
    }
}

/** The box you type a translation or your own sentence in, with the keyboard set to the card's language. */
@Composable
private fun AnswerBox(value: String, onChange: (String) -> Unit, lang: Lang, hint: String) {
    val c = Diacritica.colors
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(12.dp))).background(c.surfaceAlt)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = bodyStyle(c.inkMuted))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = bodyStyle(size = 17.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            visualTransformation = app.diacritica.ui.theme.hanTransformation(),
            keyboardOptions = app.diacritica.ui.components.keyboardFor(lang, androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        )
    }
}

/** What the check said about a typed sentence. */
@Composable
private fun FeedbackBlock(answer: String, fb: SentenceFeedback?, error: String?) {
    val c = Diacritica.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fb != null) {
            val col = when (fb.level) { 2 -> c.success; 1 -> c.warning; else -> c.error }
            Text(fb.title, style = headwordStyle(20.sp).copy(color = col))
        }
        if (answer.isNotBlank()) Text("You wrote: $answer", style = bodyStyle(c.inkMuted))
        fb?.corrected?.takeIf { it.isNotBlank() && it != answer }?.let { Text("Corrected: $it", style = bodyStyle()) }
        fb?.notes?.forEach { Text(it, style = captionStyle(c.ink)) }
        fb?.natural?.let { Text("More natural: $it", style = captionStyle()) }
        if (fb == null && answer.isNotBlank() && error == null) Text("Compare with the answer below and rate yourself.", style = captionStyle())
        error?.let { Text(it, style = captionStyle(c.error)) }
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
            CardKind.SENTENCE -> app.diacritica.ui.components.TappableText(item.card.back ?: "", item.lang, style = headwordStyle())
            CardKind.TRANSLATE -> {
                FeedbackBlock(state.answer, state.feedback, state.checkError)
                state.task?.let { t ->
                    SectionLabel(if (t.references.size > 1) "Answers" else "Answer")
                    t.references.take(3).forEach { r -> app.diacritica.ui.components.TappableText(r, item.lang, style = headwordStyle(19.sp)) }
                } ?: w?.let { WordTitle(it, large = true) }
                w?.let { Text("Word: " + (it.article?.let { a -> "$a " } ?: "") + it.word + (it.pinyin?.let { p -> "  $p" } ?: ""), style = captionStyle()) }
            }
            CardKind.USE -> {
                FeedbackBlock(state.answer, state.feedback, state.checkError)
                w?.let { ExampleLine(it) }
            }
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
private fun RatingRow(previews: Map<Fsrs.Rating, String>, suggested: Fsrs.Rating?, onRate: (Fsrs.Rating) -> Unit) {
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
            val emphasize = suggested == r
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
