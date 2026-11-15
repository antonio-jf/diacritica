package app.diacritica.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.SurfaceStyle

// ---------------------------------------------------------------- text styles from tokens

@Composable
fun titleStyle(): TextStyle = TextStyle(
    fontFamily = Diacritica.fonts.display, fontSize = Diacritica.type.title,
    fontWeight = FontWeight(Diacritica.type.titleWeight), color = Diacritica.colors.ink,
)

@Composable
fun headwordStyle(size: TextUnit = Diacritica.type.headword): TextStyle = TextStyle(
    fontFamily = Diacritica.fonts.display, fontSize = size,
    fontWeight = FontWeight(Diacritica.type.headwordWeight), color = Diacritica.colors.ink,
)

@Composable
fun bodyStyle(color: Color = Diacritica.colors.ink, size: TextUnit = Diacritica.type.body): TextStyle =
    TextStyle(fontFamily = Diacritica.fonts.body, fontSize = size, color = color, lineHeight = size * 1.45f)

@Composable
fun captionStyle(color: Color = Diacritica.colors.inkMuted): TextStyle =
    TextStyle(fontFamily = Diacritica.fonts.body, fontSize = Diacritica.type.caption, color = color, lineHeight = Diacritica.type.caption * 1.4f)

@Composable
fun exampleStyle(): TextStyle = TextStyle(
    fontFamily = Diacritica.fonts.reading, fontSize = Diacritica.type.caption, color = Diacritica.colors.inkMuted,
    fontStyle = if (Diacritica.type.exampleItalic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = Diacritica.type.caption * 1.4f,
)

/** Small section/language label: caps + tracking in Paper/Index, mono in Midnight. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Diacritica.colors.inkMuted) {
    val t = Diacritica.type
    Text(
        text = if (t.capsLabels) text.uppercase() else text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = Diacritica.fonts.label, fontSize = t.label, fontWeight = FontWeight.SemiBold,
            letterSpacing = if (t.capsLabels) t.labelTracking else 0.sp, color = color,
        ),
    )
}

// ---------------------------------------------------------------- language badge

/** Language code; becomes a colored chip when "Language colors" is on or the theme uses chips. */
@Composable
fun LangBadge(lang: Lang, modifier: Modifier = Modifier, boxed: Boolean = false) {
    val tokens = Diacritica.tokens
    if (tokens.languageColors || boxed) {
        val lc = tokens.lang(lang)
        val bg = if (tokens.languageColors) lc.container else Diacritica.colors.accentSoft
        val fg = if (tokens.languageColors) lc.content else Diacritica.colors.onAccentSoft
        Box(
            modifier
                .size(36.dp)
                .clip(RoundedCornerShape(minOf(Diacritica.shapes.chip, 12.dp)))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(lang.short, style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg))
        }
    } else {
        SectionLabel(lang.short, modifier.widthIn(min = 30.dp))
    }
}

// ---------------------------------------------------------------- surfaces

/** A group of content drawn according to the theme's surface style. */
@Composable
fun PolySurface(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.card)
    val style = Diacritica.tokens.surfaceStyle
    var m = modifier.fillMaxWidth().clip(shape)
    m = when (style) {
        SurfaceStyle.HAIRLINE -> m.background(if (highlighted) c.surfaceAlt else c.surface)
        SurfaceStyle.TONAL -> m.background(c.surface)
        SurfaceStyle.BORDERED -> m.background(c.surface).border(BorderStroke(1.dp, if (highlighted) c.accent else c.line), shape)
        SurfaceStyle.RULED -> m.background(if (highlighted) c.accentSoft else c.surface).border(BorderStroke(2.dp, c.rule), shape)
    }
    if (highlighted && style == SurfaceStyle.TONAL) m = m.border(BorderStroke(2.dp, c.accent), shape)
    // Paper has no card boxes; a tappable card gets a thin outline so it reads as a button.
    if (onClick != null && style == SurfaceStyle.HAIRLINE) m = m.border(BorderStroke(1.dp, c.ink.copy(alpha = 0.18f)), shape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun Hairline(modifier: Modifier = Modifier, heavy: Boolean = false) {
    val c = Diacritica.colors
    HorizontalDivider(modifier, thickness = if (heavy) 2.dp else 1.dp, color = if (heavy) c.rule else c.line)
}

// ---------------------------------------------------------------- buttons & chips

@Composable
fun PolyIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Diacritica.colors.accent, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun PolyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.button)
    val bg = if (primary) (if (Diacritica.tokens.heavyRules) c.ink else c.accent) else Color.Transparent
    val fg = if (primary) (if (Diacritica.tokens.heavyRules) c.ground else c.onAccent) else c.ink
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(bg)
            .then(if (!primary) Modifier.border(BorderStroke(if (Diacritica.tokens.heavyRules) 2.dp else 1.dp, if (Diacritica.tokens.heavyRules) c.rule else c.ink.copy(alpha = 0.6f)), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = if (enabled) 1f else 0.4f)))
    }
}

/** Selectable chip: sense picker, source language, settings choices. */
@Composable
fun PolyChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.chip)
    val style = Diacritica.tokens.surfaceStyle
    val label = TextStyle(
        fontFamily = Diacritica.fonts.body, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            selected && style == SurfaceStyle.TONAL -> c.onAccent
            selected && style == SurfaceStyle.BORDERED -> c.accent
            selected && style == SurfaceStyle.RULED -> c.ground
            selected -> c.onAccentSoft
            // Unselected chips stay in full ink so they read as buttons, not disabled text.
            else -> c.ink
        },
    )
    val m = when (style) {
        // Paper: an outlined box, filled with the soft accent when selected, so every option is clearly a button.
        SurfaceStyle.HAIRLINE -> modifier.clip(shape).background(if (selected) c.accentSoft else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.ink.copy(alpha = 0.28f), shape)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
        SurfaceStyle.TONAL -> modifier.clip(shape).background(if (selected) c.accent else c.surface)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
        SurfaceStyle.BORDERED -> modifier.clip(shape).border(1.dp, if (selected) c.accent else c.line, shape)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
        SurfaceStyle.RULED -> modifier.background(if (selected) c.ink else Color.Transparent).border(if (selected) 0.dp else 1.dp, c.line)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
    }
    Column(m) {
        Text(text, style = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Diacritica.spacing.screen).padding(top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = titleStyle(), modifier = Modifier.weight(1f))
            trailing()
        }
        if (Diacritica.tokens.heavyRules) Hairline(heavy = true)
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))
