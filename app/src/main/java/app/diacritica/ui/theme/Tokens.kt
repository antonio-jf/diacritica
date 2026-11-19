package app.diacritica.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang

/**
 * Design tokens. Every screen and every layout reads ONLY these values (through [Diacritica]),
 * never hard-coded colors, fonts or radii. That is what keeps the app consistent when the user
 * changes a section's layout: the theme is global, layouts only rearrange.
 */
@Immutable
data class PolyColors(
    val ground: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val inkMuted: Color,
    val line: Color,
    /** Heavy rules (Index uses ink; others use [line]). */
    val rule: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val isDark: Boolean,
) {
    /** Filled star for saved words: gold in every theme, so it stands apart from the muted outline. */
    val savedStar: Color get() = if (isDark) Color(0xFFF2C14E) else Color(0xFFC08A00)
}

@Immutable
data class LangColor(val container: Color, val content: Color)

/** How grouped content (a result, a panel) is drawn. */
enum class SurfaceStyle {
    /** Paper: no box, hairline separators. */
    HAIRLINE,
    /** Tiles: filled tonal card, no border. */
    TONAL,
    /** Midnight: filled card with a 1dp border. */
    BORDERED,
    /** Index: white box with a 2dp ink border, no fill change. */
    RULED,
}

@Immutable
data class PolyShapes(val card: Dp, val chip: Dp, val button: Dp, val field: Dp)

@Immutable
data class PolyFonts(
    /** Headwords and screen titles. */
    val display: FontFamily,
    /** Running text. */
    val body: FontFamily,
    /** Small labels: language codes, section captions. */
    val label: FontFamily,
    /** Reading text (examples, later the Reader). */
    val reading: FontFamily,
    /** Chinese characters, when the user picked a font for them (null: the system's default). */
    val han: FontFamily? = null,
)

@Immutable
data class PolyType(
    val title: TextUnit = 26.sp,
    val headword: TextUnit = 21.sp,
    val headwordLarge: TextUnit = 30.sp,
    val body: TextUnit = 15.sp,
    val caption: TextUnit = 13.sp,
    val label: TextUnit = 11.sp,
    /** Uppercase + tracking for small labels (Paper, Index). */
    val capsLabels: Boolean = true,
    val labelTracking: TextUnit = 0.9.sp,
    val exampleItalic: Boolean = true,
    val titleWeight: Int = 600,
    val headwordWeight: Int = 500,
)

@Immutable
data class PolySpacing(val screen: Dp = 20.dp, val row: Dp = 14.dp, val gap: Dp = 10.dp)

@Immutable
data class PolyTokens(
    val id: String,
    val colors: PolyColors,
    val fonts: PolyFonts,
    val type: PolyType,
    val shapes: PolyShapes,
    val spacing: PolySpacing,
    val surfaceStyle: SurfaceStyle,
    /** Index draws a heavy rule under headers and above the nav. */
    val heavyRules: Boolean,
    val languageColors: Boolean,
    private val langPalette: Map<Lang, LangColor>,
) {
    fun lang(l: Lang): LangColor = langPalette.getValue(l)
}

val LocalDiacritica = staticCompositionLocalOf<PolyTokens> { error("DiacriticaTheme not set") }

/** Access tokens in composables: `Diacritica.colors.accent`. */
object Diacritica {
    val tokens: PolyTokens
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalDiacritica.current
    val colors: PolyColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalDiacritica.current.colors
    val fonts: PolyFonts
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalDiacritica.current.fonts
    val type: PolyType
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalDiacritica.current.type
    val shapes: PolyShapes
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalDiacritica.current.shapes
    val spacing: PolySpacing
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalDiacritica.current.spacing
}
