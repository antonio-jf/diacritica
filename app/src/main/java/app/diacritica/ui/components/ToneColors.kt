package app.diacritica.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.diacritica.data.PinyinTones
import app.diacritica.ui.theme.Diacritica

/** Whether pinyin is colored by tone (Settings › Appearance). Provided by DiacriticaTheme. */
val LocalToneColors = staticCompositionLocalOf { true }

/** The five tone colors for the current theme (light or dark). 5 = neutral tone. */
@Composable
@ReadOnlyComposable
fun toneColor(tone: Int): Color {
    val c = Diacritica.colors
    return if (c.isDark) when (tone) {
        1 -> Color(0xFFFF7A7A); 2 -> Color(0xFF7CCB7F); 3 -> Color(0xFF6FB3FF); 4 -> Color(0xFFD69CF0); else -> c.inkMuted
    } else when (tone) {
        1 -> Color(0xFFD32F2F); 2 -> Color(0xFF2E7D32); 3 -> Color(0xFF1565C0); 4 -> Color(0xFF8E24AA); else -> c.inkMuted
    }
}

/** [pinyin] with each syllable in its tone's color, or plain when the setting is off. */
@Composable
fun tonedPinyin(pinyin: String): AnnotatedString {
    if (!LocalToneColors.current) return AnnotatedString(pinyin)
    val colors = (1..5).map { toneColor(it) }
    return remember(pinyin, colors) {
        buildAnnotatedString { appendToned(pinyin, colors) }
    }
}

/** Adds [pinyin] colored by tone ([colors] for tones 1–5), for text built from several parts. */
fun AnnotatedString.Builder.appendToned(pinyin: String, colors: List<Color>) {
    PinyinTones.split(pinyin).forEach { p ->
        if (p.tone == 0) append(p.text) else withStyle(SpanStyle(color = colors[p.tone - 1])) { append(p.text) }
    }
}

/** The tone colors for [appendToned], or null when the setting is off. */
@Composable
fun toneColorsOrNull(): List<Color>? = if (LocalToneColors.current) (1..5).map { toneColor(it) } else null

/** A pinyin line: tone colored when the setting is on, else in [style]'s own color. */
@Composable
fun PinyinText(
    pinyin: String, style: TextStyle, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, textAlign: TextAlign? = null,
) {
    val s = if (textAlign != null) style.copy(textAlign = textAlign) else style
    BasicText(tonedPinyin(pinyin), modifier, style = s, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * For text that mixes pinyin with other words: `val py = pinyinAppender()` then
 * `buildAnnotatedString { append("老实 "); py("lǎoshi"); append(" · honest") }`.
 */
@Composable
fun pinyinAppender(): AnnotatedString.Builder.(String) -> Unit {
    val colors = toneColorsOrNull()
    return { p -> if (colors == null) append(p) else appendToned(p, colors) }
}
