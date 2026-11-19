package app.diacritica.ui.components

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.diacritica.ui.theme.Han
import app.diacritica.ui.theme.LocalHanScale

/*
 * The app's Text: the same as Material's, except Chinese characters are drawn at the
 * "Chinese characters" size from Settings. Latin text is untouched, and plain Latin
 * strings go straight through to Material without building an AnnotatedString.
 */

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val scale = LocalHanScale.current
    val hanFont = app.diacritica.ui.theme.LocalHanFont.current
    if ((scale == 1f && hanFont == null) || !Han.hasHan(text)) {
        androidx.compose.material3.Text(
            text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
            textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style,
        )
    } else {
        val scaled = remember(text, scale, hanFont) { Han.scale(text, scale, hanFont) }
        androidx.compose.material3.Text(
            scaled, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
            textAlign, roomyLineHeight(lineHeight, fontSize, style, scale), overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout ?: {}, style,
        )
    }
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val scale = LocalHanScale.current
    val hanFont = app.diacritica.ui.theme.LocalHanFont.current
    val shown = remember(text, scale, hanFont) { Han.scale(text, scale, hanFont) }
    val lh = if (shown === text) lineHeight else roomyLineHeight(lineHeight, fontSize, style, scale)
    androidx.compose.material3.Text(
        shown, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
        textAlign, lh, overflow, softWrap, maxLines, minLines, inlineContent, onTextLayout, style,
    )
}

/**
 * A fixed line height sized for Latin text can be too tight for enlarged characters,
 * so lines would overlap. Keep at least 1.3x the Chinese glyph size.
 */
private fun roomyLineHeight(lineHeight: TextUnit, fontSize: TextUnit, style: TextStyle, scale: Float): TextUnit {
    val lh = if (lineHeight != TextUnit.Unspecified) lineHeight else style.lineHeight
    val fs = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize
    val need = scale * 1.3f
    return when {
        lh.isEm -> if (lh.value < need) need.em else lineHeight
        lh.isSp && fs.isSp -> if (lh.value < fs.value * need) (fs.value * need).sp else lineHeight
        else -> lineHeight
    }
}
