package app.diacritica.ui.components

import androidx.compose.foundation.layout.defaultMinSize
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
                .defaultMinSize(36.dp, 36.dp)
                .clip(RoundedCornerShape(minOf(Diacritica.shapes.chip, 12.dp)))
                .background(bg)
                .padding(horizontal = 4.dp),
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
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg)
            // Secondary: a soft tonal fill instead of an outline (the newspaper theme keeps its heavy rule).
            .then(
                when {
                    primary -> Modifier
                    Diacritica.tokens.heavyRules -> Modifier.border(BorderStroke(2.dp, c.rule), shape)
                    else -> Modifier.background(c.ink.copy(alpha = if (c.isDark) 0.10f else 0.06f))
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = if (enabled) 1f else 0.4f)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/**
 * A width for a column of text (labels, numbers) that grows with the system font size, so larger text gets
 * more room instead of wrapping letter by letter. Never shrinks below [this]; capped at 1.6x so a label column
 * can't take over a phone screen.
 */
@Composable
fun Dp.textScaled(): Dp = this * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

/** Selectable chip: sense picker, source language, settings choices. */
@Composable
fun PolyChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, iconTint: Color? = null) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.chip)
    val style = Diacritica.tokens.surfaceStyle
    val label = TextStyle(
        fontFamily = Diacritica.fonts.body, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            selected && style == SurfaceStyle.TONAL -> c.onAccent
            selected && style == SurfaceStyle.BORDERED -> c.onAccentSoft
            selected && style == SurfaceStyle.RULED -> c.ground
            selected -> c.onAccentSoft
            // Unselected chips stay in full ink so they read as buttons, not disabled text.
            else -> c.ink
        },
    )
    val m = when (style) {
        // Paper: a soft pill, tinted with the accent when selected. No outlines: the fill alone reads as a button.
        SurfaceStyle.HAIRLINE -> modifier.clip(shape).background(if (selected) c.accentSoft else c.ink.copy(alpha = if (c.isDark) 0.10f else 0.06f))
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
        SurfaceStyle.TONAL -> modifier.clip(shape).background(if (selected) c.accent else c.surface)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
        SurfaceStyle.BORDERED -> modifier.clip(shape).background(if (selected) c.accentSoft else c.ink.copy(alpha = if (c.isDark) 0.10f else 0.06f))
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
        SurfaceStyle.RULED -> modifier.background(if (selected) c.ink else Color.Transparent).border(if (selected) 0.dp else 1.dp, c.line)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
    }
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, tint = iconTint ?: label.color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
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

/**
 * What an empty list or screen is for and how it fills up: a short title, one line of explanation and,
 * optionally, a button that starts filling it.
 */
@Composable
fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Diacritica.spacing.screen, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = bodyStyle().copy(fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        Text(body, style = captionStyle().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(6.dp))
            PolyButton(action, onAction, primary = false)
        }
    }
}

/**
 * "Offline: Gemini paths need a connection." A quiet one-line notice with an optional Retry, used wherever
 * something needs the internet so the offline state looks and works the same everywhere.
 */
@Composable
fun OfflineNotice(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = Diacritica.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(12.dp))).background(c.surface)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PolyIcons.CloudOff, contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = captionStyle(c.ink), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (onRetry != null) {
            Text(
                "Retry", style = captionStyle(c.accent).copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}
