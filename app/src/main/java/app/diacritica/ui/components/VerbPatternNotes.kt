package app.diacritica.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.WordInfo
import app.diacritica.ui.theme.Diacritica

/**
 * German verbs: "sich freuen auf + Akk. · to look forward to", one line per pattern (up to [max]). Nothing for
 * other words or when the dictionary has no patterns for the verb.
 */
@Composable
fun VerbPatternNotes(w: WordInfo, modifier: Modifier = Modifier, max: Int = 5) {
    if (w.patterns.isEmpty()) return
    val c = Diacritica.colors
    Column(modifier.padding(top = 4.dp)) {
        Text("Used with", style = captionStyle())
        w.patterns.distinctBy { it.text(w.word) }.take(max).forEach { p ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)) { append(p.text(w.word)) }
                    if (p.gloss.isNotBlank()) withStyle(SpanStyle(color = c.inkMuted)) { append("  ·  ${p.gloss}") }
                },
                style = bodyStyle(size = 14.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
