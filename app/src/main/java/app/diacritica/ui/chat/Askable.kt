package app.diacritica.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.diacritica.ui.books.AboveSelection
import app.diacritica.ui.books.CapturingClipboard
import app.diacritica.ui.books.SelectionPill
import app.diacritica.ui.books.ToolbarAction
import app.diacritica.ui.components.PolyIcons

/**
 * Opens the Ask chat with [text] attached as a quote. Provided by the app's root; null where there's no chat
 * (the word sheet over other apps).
 */
val LocalAsk = staticCompositionLocalOf<((text: String, source: String?) -> Unit)?> { null }

private class AskRequest(val rect: Rect, val copy: (() -> Unit)?, val selectAll: (() -> Unit)?)

/** A selection toolbar for read-only text: Ask, Copy and Select all, drawn by the app. */
private class AskToolbar : TextToolbar {
    var request by mutableStateOf<AskRequest?>(null)
        private set

    override val status: TextToolbarStatus get() = if (request != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        request = AskRequest(rect, onCopyRequested, onSelectAllRequested)
    }

    override fun hide() { request = null }
}

/**
 * Makes the text in [content] selectable (long-press, drag the handles), with **Ask** next to Copy: it opens
 * the Ask chat with the selection quoted. [source] says where it came from ("Rehearse feedback"…), for Gemini.
 * Only for read-only text: text fields keep the system menu. [content] should be one element (a Column…).
 */
@Composable
fun Askable(source: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ask = LocalAsk.current
    val realClipboard = LocalClipboardManager.current
    val clipboard = remember(realClipboard) { CapturingClipboard(realClipboard) }
    val toolbar = remember { AskToolbar() }
    // Bumping this rebuilds the selection container, which drops the selection once an action is done.
    var epoch by remember { mutableIntStateOf(0) }
    // A Box, so the (zero-size) popup doesn't take a slot in the parent's spacing.
    Box(modifier) {
    CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboardManager provides clipboard) {
        key(epoch) { SelectionContainer { content() } }
    }
    toolbar.request?.let { r ->
        val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
        fun take(action: (String) -> Unit) {
            val text = r.copy?.let { clipboard.capture(it) }?.trim()
            toolbar.hide()
            epoch++
            if (!text.isNullOrEmpty()) action(text)
        }
        val actions = buildList {
            if (r.copy != null && ask != null) add(ToolbarAction(PolyIcons.Chat, "Ask") { take { ask(it, source) } })
            if (r.copy != null) add(ToolbarAction(PolyIcons.Copy, "Copy") { take { clipboard.setReal(it) } })
            r.selectAll?.let { all -> add(ToolbarAction(PolyIcons.Explain, "Select all") { all() }) }
        }
        if (actions.isNotEmpty()) {
            Popup(popupPositionProvider = AboveSelection(r.rect, gap), properties = PopupProperties(focusable = false)) {
                SelectionPill(actions)
            }
        }
    }
    }
}
