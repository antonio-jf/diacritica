package app.diacritica.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import app.diacritica.DiacriticaApp
import app.diacritica.data.Lang

/** Set by [WordTapHost] when "Tap words to look them up" is on: opens a word from [TappableText]. */
val LocalWordTap = staticCompositionLocalOf<((text: String, offset: Int, lang: Lang?) -> Unit)?> { null }

/**
 * Lets the practice sentences inside [content] be tapped for their meaning (the shared word sheet), when
 * [enabled]. Pass [lookup] to reuse a screen's own (Review); otherwise one is made for this screen.
 */
@Composable
fun WordTapHost(
    enabled: Boolean,
    source: String,
    lookup: WordLookup? = null,
    onOpenDictionary: ((Lang, String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled) { content(); return }
    val app = LocalContext.current.applicationContext as DiacriticaApp
    val scope = rememberCoroutineScope()
    val own = remember(lookup) { lookup ?: WordLookup(app.container, scope, source) }
    CompositionLocalProvider(LocalWordTap provides { text, offset, lang -> own.openAt(text, offset, lang) }) { content() }
    // A screen with its own lookup already shows its sheet.
    if (lookup == null) WordLookupSheet(own, onOpenDictionary)
}

/** Text whose words open the word sheet when tapped, if a [WordTapHost] above turned that on; plain text otherwise. */
@Composable
fun TappableText(text: AnnotatedString, lang: Lang?, style: TextStyle, modifier: Modifier = Modifier) {
    val tap = LocalWordTap.current
    if (tap == null) { Text(text, style = style, modifier = modifier); return }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val current by rememberUpdatedState(text.text)
    Text(
        text, style = style, onTextLayout = { layout = it },
        modifier = modifier.pointerInput(lang) {
            detectTapGestures { pos -> layout?.let { tap(current, it.getOffsetForPosition(pos), lang) } }
        },
    )
}

@Composable
fun TappableText(text: String, lang: Lang?, style: TextStyle, modifier: Modifier = Modifier) =
    TappableText(AnnotatedString(text), lang, style, modifier)
