package app.diacritica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.FalseFriendWarning
import app.diacritica.ui.theme.Diacritica

/**
 * "False friend: embarazada ≠ embarrassed" with what each word really means.
 * [compact] drops the second line, for the Reader's tooltip.
 */
@Composable
fun FalseFriendNote(w: FalseFriendWarning, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.warning.copy(alpha = 0.10f))
            .border(1.dp, c.warning.copy(alpha = 0.55f), shape).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.warning, fontWeight = FontWeight.SemiBold)) { append("⚠ False friend  ") }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(w.word) }
                append(" ≠ ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(w.otherWord) }
                append(" (${w.otherLang.code.uppercase()})")
            },
            style = bodyStyle(size = if (compact) 13.sp else 15.sp),
        )
        if (compact) {
            Text("${w.word} = ${w.means}", style = captionStyle(c.ink))
        } else {
            Text(
                "${w.word} means “${w.means}”, in ${w.otherLang.englishName}: ${w.rightWord}. " +
                    "${w.otherWord} means “${w.otherMeans}”, in ${w.lang.englishName}: ${w.sayInstead}.",
                style = captionStyle(c.ink),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
