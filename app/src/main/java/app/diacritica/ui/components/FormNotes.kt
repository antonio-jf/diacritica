package app.diacritica.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.diacritica.data.FormNote
import app.diacritica.ui.theme.Diacritica

/**
 * "nahm: first/third-person singular preterite of nehmen ›". Tapping a line looks up the base word.
 * Shows at most [max] notes (an ambiguous form like Spanish "fui" has two: ir and ser).
 */
@Composable
fun FormNotes(word: String, forms: List<FormNote>, onLemma: ((String) -> Unit)?, modifier: Modifier = Modifier, max: Int = 2) {
    if (forms.isEmpty()) return
    val c = Diacritica.colors
    Column(modifier) {
        forms.take(max).forEach { f ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(word) }
                    append(": ${f.description} of ")
                    withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)) { append(f.lemma) }
                    if (onLemma != null) withStyle(SpanStyle(color = c.accent)) { append(" ›") }
                },
                style = captionStyle(c.ink),
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .then(if (onLemma != null) Modifier.clickable { onLemma(f.lemma) } else Modifier)
                    .padding(vertical = 3.dp),
            )
        }
    }
}
