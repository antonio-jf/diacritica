package app.diacritica.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.theme.Diacritica

/**
 * The normal Android text selection (long-press, drag the handles), with the app's own actions in place of the
 * system's Copy / Select all menu.
 *
 * Compose doesn't hand out the selected text, only a "copy" callback, so [CapturingClipboard] sits in front of the
 * real clipboard: while [capture] runs, a copy is caught instead of written. Your clipboard changes only when you
 * pick Copy.
 */
internal class CapturingClipboard(private val real: ClipboardManager) : ClipboardManager {
    private var capturing = false
    private var caught: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        if (capturing) caught = annotatedString else real.setText(annotatedString)
    }

    override fun getText(): AnnotatedString? = real.getText()

    /** Runs [copy] (the selection's copy action) and returns what it would have copied. */
    fun capture(copy: () -> Unit): String? {
        capturing = true
        caught = null
        try { copy() } finally { capturing = false }
        return caught?.text
    }

    fun setReal(text: String) = real.setText(AnnotatedString(text))
}

/** Where the selection is on screen and how to copy it; null while nothing is selected. */
internal class SelectionRequest(val rect: Rect, val copy: (() -> Unit)?)

internal class SelectionToolbar : TextToolbar {
    var request by mutableStateOf<SelectionRequest?>(null)
        private set

    override val status: TextToolbarStatus get() = if (request != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        request = SelectionRequest(rect, onCopyRequested)
    }

    override fun hide() { request = null }
}

/** Places the toolbar above the selection, or below it when there's no room above; kept inside the window. */
internal class AboveSelection(private val rect: Rect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (rect.center.x - popupContentSize.width / 2f).toInt()
            .coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
        val above = rect.top.toInt() - popupContentSize.height - gap
        val y = if (above >= gap * 6) above else (rect.bottom.toInt() + gap * 4).coerceAtMost(windowSize.height - popupContentSize.height - gap)
        return IntOffset(x, y)
    }
}

internal class ToolbarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** A compact dark pill of icon + label actions, like the system's selection menu. */
@Composable
internal fun SelectionPill(actions: List<ToolbarAction>) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.shadow(6.dp, shape).clip(shape).background(c.ink).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { a ->
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = a.onClick).widthIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, contentDescription = null, tint = c.ground, modifier = Modifier.size(18.dp))
                Text(a.label, style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 10.5.sp, color = c.ground), maxLines = 1)
            }
        }
    }
}
