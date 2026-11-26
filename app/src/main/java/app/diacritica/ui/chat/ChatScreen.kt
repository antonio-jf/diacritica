package app.diacritica.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.DiacriticaApp
import app.diacritica.chat.ChatMessage
import app.diacritica.chat.Quote
import app.diacritica.data.Lang
import app.diacritica.settings.AppSettings
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.InlineMarkdown
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.Diacritica

/**
 * Ask: a chat with Gemini about grammar, words and phrases. Text selected anywhere in the app (or in another
 * app, with "Ask Diacritica") arrives here as a quote above the input, ready for your question.
 */
@Composable
fun ChatScreen(settings: AppSettings, onBack: (() -> Unit)?, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val chat = remember { (context.applicationContext as DiacriticaApp).container.chat }
    val st by chat.state.collectAsStateWithLifecycle()
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val noKey = settings.geminiKey.isBlank()

    // Keep the newest message (or "Thinking…") in view.
    val itemCount = (if (noKey) 1 else 0) + (if (st.messages.isEmpty()) 1 else 0) + st.messages.size +
        (if (st.busy) 1 else 0) + (if (st.error != null) 1 else 0)
    LaunchedEffect(itemCount) { if (itemCount > 0) list.animateScrollToItem(itemCount - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Ask", Modifier.weight(1f)) {
                if (st.messages.isNotEmpty() || st.pendingQuote != null) {
                    PolyIconButton(PolyIcons.NewChat, "New chat", { chat.newChat(); input = "" }, tint = c.inkMuted)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = pad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (noKey) item(key = "key") { KeyNotice(onOpenSettings) }
            if (st.messages.isEmpty()) item(key = "intro") {
                Intro(settings.activeLanguages) { q -> input = q }
            }
            items(st.messages, key = { it.id }) { m ->
                if (m.fromUser) UserBubble(m) else ModelAnswer(m)
            }
            if (st.busy) item(key = "busy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Thinking…", style = captionStyle(c.accent), modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = chat::stop) { Text("Stop", style = captionStyle(c.inkMuted)) }
                }
            }
            st.error?.let { err ->
                item(key = "error") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(err, style = captionStyle(c.error))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (st.messages.lastOrNull()?.fromUser == true) {
                                LinkText("Try again") { chat.retry() }
                                LinkText("Edit question") { chat.takeBackLast()?.let { input = it.text } }
                            }
                        }
                    }
                }
            }
        }
        Composer(
            value = input, onChange = { input = it },
            quote = st.pendingQuote, onClearQuote = chat::clearQuote,
            canSend = !st.busy && !noKey && (input.isNotBlank() || st.pendingQuote != null),
            onSend = { chat.send(input); input = "" },
        )
    }
}

@Composable
private fun KeyNotice(onOpenSettings: () -> Unit) {
    val c = Diacritica.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ask uses Gemini with your own free API key, like Rehearse and Tests.", style = bodyStyle(size = 15.sp))
        PolyButton("Add a key in Settings", onOpenSettings, primary = false)
    }
}

/** What Ask is for, with a few questions to start from (tapping one puts it in the box). */
@Composable
private fun Intro(langs: List<Lang>, onPick: (String) -> Unit) {
    val c = Diacritica.colors
    val ideas = buildList {
        if (Lang.FR in langs) add("Passé composé or imparfait: how do I choose?")
        if (Lang.DE in langs) add("When does German use the dative?")
        if (Lang.IT in langs) add("When do I need the congiuntivo in Italian?")
        if (Lang.ZH in langs) add("What's the difference between 了 and 过?")
        if (Lang.ES in langs && size < 3) add("¿Cuándo se usa «por» y cuándo «para»?")
        add("Is this sentence correct? ")
    }.take(5)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            InlineMarkdown.render(
                "Ask anything about grammar, words or phrases in your languages. To ask about a sentence, select it " +
                    "(in Books, Rehearse, Tests, lessons or Translate) and tap **Ask**, or tap Ask in the Reader's sentence bar. " +
                    "In other apps, select text and pick *Ask Diacritica*.",
                c.ink,
            ),
            style = bodyStyle(c.inkMuted, 15.sp),
        )
        Gap(2.dp)
        ideas.forEach { q ->
            Text(
                q.trim(), style = bodyStyle(c.accent, 15.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable { onPick(q) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    val c = Diacritica.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(c.accentSoft)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            m.quote?.let { QuoteBlock(it, c.onAccentSoft) }
            if (m.text.isNotBlank()) Text(m.text, style = bodyStyle(c.onAccentSoft, 16.sp))
            else if (m.quote != null) Text("Explain this", style = captionStyle(c.onAccentSoft.copy(alpha = 0.75f)))
        }
    }
}

@Composable
private fun ModelAnswer(m: ChatMessage) {
    val c = Diacritica.colors
    val clipboard = LocalClipboardManager.current
    Column {
        // Select part of an answer and tap Ask to follow up on exactly that.
        Askable(source = "from your earlier answer") {
            Text(InlineMarkdown.render(m.text, c.ink), style = bodyStyle(size = 16.sp))
        }
        Row(Modifier.padding(top = 2.dp)) {
            PolyIconButton(PolyIcons.Copy, "Copy answer", { clipboard.setText(AnnotatedString(InlineMarkdown.render(m.text).text)) }, tint = c.inkMuted)
        }
    }
}

/** A quoted passage: a bar on the left, where it's from, and the text (cut after a few lines). */
@Composable
private fun QuoteBlock(q: Quote, color: androidx.compose.ui.graphics.Color, maxLines: Int = 6) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.5f)))
        Column(Modifier.padding(start = 8.dp)) {
            q.source?.let { Text(it, style = captionStyle(color.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                q.text, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 15.sp, lineHeight = 21.sp, color = color),
            )
        }
    }
}

@Composable
private fun Composer(
    value: String, onChange: (String) -> Unit,
    quote: Quote?, onClearQuote: () -> Unit,
    canSend: Boolean, onSend: () -> Unit,
) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(18.dp))
    Column(Modifier.fillMaxWidth().background(c.ground).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        quote?.let { q ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { QuoteBlock(q, c.ink, maxLines = 3) }
                PolyIconButton(PolyIcons.Close, "Remove quote", onClearQuote, tint = c.inkMuted)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.weight(1f).clip(shape).background(c.surface)
                    .border(BorderStroke(if (Diacritica.tokens.heavyRules) 2.dp else 1.dp, if (Diacritica.tokens.heavyRules) c.rule else c.line), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(if (quote != null) "Ask about this (or just send to have it explained)" else "Ask about grammar, a word, a phrase…", style = bodyStyle(c.inkMuted, 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value, onValueChange = onChange, maxLines = 6,
                    textStyle = TextStyle(fontFamily = Diacritica.fonts.body, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp).semantics { contentDescription = "Your question" },
                )
            }
            PolyIconButton(PolyIcons.Send, "Send", { if (canSend) onSend() }, tint = if (canSend) c.accent else c.inkMuted.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text, style = captionStyle(Diacritica.colors.accent),
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
