package app.diacritica.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import app.diacritica.data.WordInfo
import app.diacritica.ui.theme.Diacritica

/** "la clé (f) · /kle/ — key; crucial", the text shared or copied for a word. */
fun shareTextFor(w: WordInfo, translations: List<WordInfo> = emptyList()): String = buildString {
    val head = w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word
    append(head)
    w.traditional?.let { append(" ($it)") }
    w.pinyin?.let { append(" · $it") }
    w.gender?.let { append(" ($it)") }
    w.ipa?.let { append(" · /$it/") }
    w.gloss?.let { append(" — $it") }
    translations.forEach { t ->
        val th = t.article?.let { a -> if (a.endsWith("'")) a + t.word else "$a ${t.word}" } ?: t.word
        append("\n${t.lang.code.uppercase()}: $th" + (t.pinyin?.let { " ($it)" } ?: ""))
    }
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Copy and Share buttons for a word. */
@Composable
fun CopyShareButtons(text: () -> String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val c = Diacritica.colors
    Row {
        PolyIconButton(PolyIcons.Copy, "Copy", { clipboard.setText(AnnotatedString(text())) }, tint = c.inkMuted)
        PolyIconButton(PolyIcons.Share, "Share", { shareText(context, text()) }, tint = c.inkMuted)
    }
}
