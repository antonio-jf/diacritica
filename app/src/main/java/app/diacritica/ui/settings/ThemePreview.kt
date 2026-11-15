package app.diacritica.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.diacritica.data.Lang
import app.diacritica.data.WordInfo
import app.diacritica.settings.AccentChoice
import app.diacritica.settings.ThemeId
import app.diacritica.settings.ThemeMode
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.dictionary.WordTitle
import app.diacritica.ui.dictionary.grammarLine
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.DiacriticaTheme
import app.diacritica.ui.theme.rememberTokens

private val sampleWords = listOf(
    WordInfo(Lang.FR, "clé", article = "la", gender = "f", plural = "clés", pos = "noun"),
    WordInfo(Lang.DE, "Schlüssel", article = "der", gender = "m", plural = "Schlüssel", pos = "noun"),
    WordInfo(Lang.ZH, "钥匙", traditional = "鑰匙", pinyin = "yàoshi", measure = "把 bǎ"),
)

/** A live miniature of the Dictionary in [theme], drawn with that theme's real tokens. */
@Composable
fun ThemePreviewCard(
    theme: ThemeId,
    mode: ThemeMode,
    accent: AccentChoice,
    languageColors: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outer = Diacritica.colors
    val tokens = rememberTokens(theme, mode, accent, languageColors)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) outer.accent else outer.line), RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        DiacriticaTheme(tokens, systemBars = false) {
            Column(Modifier.fillMaxWidth().background(Diacritica.colors.ground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(theme.title, style = titleStyle(), modifier = Modifier.weight(1f))
                    if (selected) SectionLabel("Selected", color = Diacritica.colors.accent)
                }
                Text(theme.blurb, style = captionStyle())
                if (Diacritica.tokens.heavyRules) Hairline(heavy = true) else Hairline()
                sampleWords.forEach { w ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LangBadge(w.lang, boxed = Diacritica.tokens.surfaceStyle != app.diacritica.ui.theme.SurfaceStyle.HAIRLINE)
                        Column(Modifier.weight(1f)) {
                            WordTitle(w)
                            (if (w.lang == Lang.ZH) w.pinyin else grammarLine(w))?.let { Text(it, style = captionStyle()) }
                        }
                    }
                }
            }
        }
    }
}
