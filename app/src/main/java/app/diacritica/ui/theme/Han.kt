package app.diacritica.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSp

/** How much larger Chinese characters are drawn than the text around them (1 = same size). */
val LocalHanScale = staticCompositionLocalOf { 1f }

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

    fun scale(text: AnnotatedString, scale: Float): AnnotatedString {
        if (scale == 1f || !hasHan(text)) return text
        val b = AnnotatedString.Builder(text)
        for ((s, e) in runs(text)) b.addStyle(SpanStyle(fontSize = scale.em), s, e)
        return b.toAnnotatedString()
    }

    fun scale(text: String, scale: Float): AnnotatedString = scale(AnnotatedString(text), scale)
}

/** Draws Chinese characters typed into a field at the Chinese size; the text itself is unchanged. */
class HanTransformation(private val scale: Float) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(Han.scale(text, scale), OffsetMapping.Identity)
    override fun equals(other: Any?) = other is HanTransformation && other.scale == scale
    override fun hashCode() = scale.hashCode()
}

/** For text fields where Chinese may be typed: draws it at the Chinese size. */
@androidx.compose.runtime.Composable
fun hanTransformation(): VisualTransformation {
    val scale = LocalHanScale.current
    return androidx.compose.runtime.remember(scale) { if (scale == 1f) VisualTransformation.None else HanTransformation(scale) }
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
