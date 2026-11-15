package app.diacritica.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Renders the small subset of Markdown that AI explanations use: **bold**, *italic* / _italic_,
 * `code`, and strips heading marks and list bullets. Anything else is shown as plain text.
 */
object InlineMarkdown {
    private val token = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w_])|`([^`]+)`""")

    fun render(text: String, highlight: Color? = null): AnnotatedString = buildAnnotatedString {
        val cleaned = text.lines().joinToString("\n") { line ->
            line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "").replace(Regex("^\\s*[-*+]\\s+"), "• ")
        }
        var last = 0
        for (m in token.findAll(cleaned)) {
            append(cleaned.substring(last, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() ->
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = highlight ?: Color.Unspecified)) { append(g[1].ifEmpty { g[2] }) }
                g[3].isNotEmpty() || g[4].isNotEmpty() ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3].ifEmpty { g[4] }) }
                else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[5]) }
            }
            last = m.range.last + 1
        }
        append(cleaned.substring(last))
    }
}
