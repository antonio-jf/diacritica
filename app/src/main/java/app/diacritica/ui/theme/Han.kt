package app.diacritica.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.em

/** How much larger Chinese characters are drawn than the text around them (1 = same size). */
val LocalHanScale = staticCompositionLocalOf { 1f }

/** The "Chinese characters" setting. Only the reading text of the Reader and Books uses it (see [ReadingText]). */
val LocalReadingHanScale = staticCompositionLocalOf { 1f }

/** Font for Chinese characters picked in Appearance (null: the system's default). Set by DiacriticaTheme. */
val LocalHanFont = staticCompositionLocalOf<androidx.compose.ui.text.font.FontFamily?> { null }

/** Draws [content] with Chinese characters at the reading size: for book and story text only, not menus. */
@androidx.compose.runtime.Composable
fun ReadingText(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalHanScale provides LocalReadingHanScale.current, content = content)
}

object Han {
    /** CJK ideographs plus CJK and fullwidth punctuation, so a Chinese sentence scales as one piece. */
    fun isHan(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x3134F || cp in 0x3000..0x303F || cp in 0xFF01..0xFF65

    fun hasHan(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Runs of Chinese text as [start, end) pairs. Spaces between Chinese characters join the run. */
    fun runs(s: CharSequence): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        var end = -1
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val n = Character.charCount(cp)
            if (isHan(cp)) {
                if (start < 0) start = i
                end = i + n
            } else if (start >= 0 && cp != ' '.code) {
                out += start to end
                start = -1
            }
            i += n
        }
        if (start >= 0) out += start to end
        return out
    }

    /** Chinese runs drawn at [scale] and, when set, in [family]. */
    fun scale(text: AnnotatedString, scale: Float, family: androidx.compose.ui.text.font.FontFamily? = null): AnnotatedString {
        if ((scale == 1f && family == null) || !hasHan(text)) return text
        val b = AnnotatedString.Builder(text)
        val style = SpanStyle(fontSize = if (scale == 1f) androidx.compose.ui.unit.TextUnit.Unspecified else scale.em, fontFamily = family)
        for ((s, e) in runs(text)) b.addStyle(style, s, e)
        return b.toAnnotatedString()
    }

    fun scale(text: String, scale: Float, family: androidx.compose.ui.text.font.FontFamily? = null): AnnotatedString =
        scale(AnnotatedString(text), scale, family)
}

/** Draws Chinese characters typed into a field at the Chinese size; the text itself is unchanged. */
class HanTransformation(private val scale: Float, private val family: androidx.compose.ui.text.font.FontFamily? = null) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(Han.scale(text, scale, family), OffsetMapping.Identity)
    override fun equals(other: Any?) = other is HanTransformation && other.scale == scale && other.family == family
    override fun hashCode() = scale.hashCode() * 31 + family.hashCode()
}

/** For text fields where Chinese may be typed: draws it at the Chinese size. */
@androidx.compose.runtime.Composable
fun hanTransformation(): VisualTransformation {
    val scale = LocalHanScale.current
    val family = LocalHanFont.current
    return androidx.compose.runtime.remember(scale, family) {
        if (scale == 1f && family == null) VisualTransformation.None else HanTransformation(scale, family)
    }
}

/** A field style with room between lines for enlarged Chinese characters. */
@androidx.compose.runtime.Composable
fun androidx.compose.ui.text.TextStyle.roomyForHan(): androidx.compose.ui.text.TextStyle {
    val scale = LocalHanScale.current
    val lh = lineHeight
    if (scale == 1f || !lh.isSp || !fontSize.isSp) return this
    val need = fontSize.value * scale * 1.3f
    return if (lh.value < need) copy(lineHeight = androidx.compose.ui.unit.TextUnit(need, androidx.compose.ui.unit.TextUnitType.Sp)) else this
}
