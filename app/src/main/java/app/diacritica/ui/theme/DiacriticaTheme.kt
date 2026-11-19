package app.diacritica.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import app.diacritica.settings.AccentChoice
import app.diacritica.settings.AppSettings
import app.diacritica.settings.ThemeId
import app.diacritica.settings.ThemeMode
import app.diacritica.settings.ThemeCustom

/** Resolves the user's appearance settings into one global token set. */
@Composable
fun rememberTokens(
    theme: ThemeId,
    mode: ThemeMode,
    accent: AccentChoice,
    languageColors: Boolean,
    forceDark: Boolean? = null,
    custom: ThemeCustom = ThemeCustom(),
): PolyTokens {
    val systemDark = isSystemInDarkTheme()
    val dark = forceDark ?: when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    return remember(theme, dark, accent, languageColors, custom) { buildTokens(context, theme, custom, dark, accent, languageColors) }
}

/** The colors a preset plus changes uses, before the accent choice (for swatches in Appearance). */
fun paletteFor(theme: ThemeId, custom: ThemeCustom): Themes.Palette {
    val spec = Themes.spec(theme)
    return custom.palette?.let { Themes.palette(it) } ?: Themes.Palette(spec.light, spec.dark, spec.altAccents)
}

/**
 * The preset [theme] with each part the user changed swapped in: fonts, background, corners,
 * surfaces and spacing. Type sizes and label style stay the preset's.
 */
fun buildTokens(
    context: android.content.Context, theme: ThemeId, custom: ThemeCustom, dark: Boolean, accent: AccentChoice, languageColors: Boolean,
): PolyTokens {
    val spec = Themes.spec(theme)
    val palette = paletteFor(theme, custom)
    val surface = custom.surface?.let { Themes.surfaceStyle(it) } ?: spec.surfaceStyle
    val heavy = custom.surface?.let { it == app.diacritica.settings.SurfaceChoice.BOLD } ?: spec.heavyRules
    var colors = if (dark) palette.dark else palette.light
    // Bold rules are drawn in ink; other surfaces use the normal line color.
    colors = colors.copy(rule = if (heavy) colors.ink else colors.line)
    colors = when (accent) {
        AccentChoice.THEME -> colors
        AccentChoice.ALT1, AccentChoice.ALT2 -> {
            val pair = palette.altAccents[if (accent == AccentChoice.ALT1) 0 else 1]
            val a = if (dark) pair.second else pair.first
            colors.copy(
                accent = a,
                accentSoft = a.copy(alpha = if (dark) 0.22f else 0.12f).compositeOver(colors.ground),
                onAccentSoft = if (dark) a else a.darken(0.25f),
                onAccent = if (dark) colors.ground else Color.White,
            )
        }
        AccentChoice.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            colors.copy(accent = s.primary, onAccent = s.onPrimary, accentSoft = s.primaryContainer, onAccentSoft = s.onPrimaryContainer)
        } else colors
    }
    val fontsChanged = custom.display != null || custom.body != null
    val display = custom.display?.let { Fonts.family(it) } ?: spec.fonts.display
    val body = custom.body?.let { Fonts.family(it) } ?: spec.fonts.body
    val fonts = PolyFonts(
        display = display,
        body = body,
        // With changed fonts: labels follow the body font (Space Grotesk headings keep grotesk labels, as in Index);
        // reading text follows a serif heading font, as in Paper, otherwise the body font.
        label = when {
            !fontsChanged -> spec.fonts.label
            (custom.display ?: Themes.displayFontOf(theme)) == app.diacritica.settings.FontChoice.GROTESK -> Fonts.spaceGrotesk
            else -> body
        },
        reading = when {
            !fontsChanged -> spec.fonts.reading
            (custom.display ?: Themes.displayFontOf(theme)) == app.diacritica.settings.FontChoice.SERIF -> Fonts.sourceSerif
            else -> body
        },
        han = if (custom.han == app.diacritica.settings.HanFont.SERIF) androidx.compose.ui.text.font.FontFamily.Serif else null,
    )
    return PolyTokens(
        id = theme.name + if (custom.isEmpty) "" else ":" + custom.encode(),
        colors = colors,
        fonts = fonts,
        type = spec.type,
        shapes = custom.corners?.let { Themes.shapes(it) } ?: spec.shapes,
        spacing = custom.density?.let { Themes.spacing(it) } ?: spec.spacing,
        surfaceStyle = surface,
        heavyRules = heavy,
        languageColors = languageColors,
        langPalette = Themes.langPalette(dark),
    )
}

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

/** Maps tokens onto Material 3 so stock components (text fields, switches, sliders) match the theme. */
fun PolyTokens.materialScheme(): ColorScheme {
    val c = colors
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.accentSoft, onPrimaryContainer = c.onAccentSoft,
        secondary = c.accent, onSecondary = c.onAccent,
        secondaryContainer = c.accentSoft, onSecondaryContainer = c.onAccentSoft,
        background = c.ground, onBackground = c.ink,
        surface = c.ground, onSurface = c.ink,
        surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.inkMuted,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceAlt,
        surfaceContainerLow = c.surface, surfaceContainerLowest = c.surface,
        outline = c.line, outlineVariant = c.line,
        error = c.error, onError = c.onAccent,
    )
}

@Composable
fun DiacriticaTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val tokens = rememberTokens(settings.theme, settings.mode, settings.accent, settings.languageColors, custom = settings.themeCustom)
    DiacriticaTheme(tokens, settings.textScale, hanScale = settings.hanScale, content = content)
}

@Composable
fun DiacriticaTheme(tokens: PolyTokens, textScale: Float = 1f, hanScale: Float = 1f, systemBars: Boolean = true, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = remember(density, textScale) { Density(density.density, density.fontScale * textScale) }
    val typography = remember(tokens) {
        val base = Typography()
        val body = TextStyle(fontFamily = tokens.fonts.body)
        val display = TextStyle(fontFamily = tokens.fonts.display)
        base.copy(
            displayLarge = base.displayLarge.merge(display), displayMedium = base.displayMedium.merge(display),
            displaySmall = base.displaySmall.merge(display), headlineLarge = base.headlineLarge.merge(display),
            headlineMedium = base.headlineMedium.merge(display), headlineSmall = base.headlineSmall.merge(display),
            titleLarge = base.titleLarge.merge(display), titleMedium = base.titleMedium.merge(body),
            titleSmall = base.titleSmall.merge(body), bodyLarge = base.bodyLarge.merge(body),
            bodyMedium = base.bodyMedium.merge(body), bodySmall = base.bodySmall.merge(body),
            labelLarge = base.labelLarge.merge(body), labelMedium = base.labelMedium.merge(body),
            labelSmall = base.labelSmall.merge(body),
        )
    }
    val view = LocalView.current
    if (systemBars && !view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !tokens.colors.isDark
                isAppearanceLightNavigationBars = !tokens.colors.isDark
            }
        }
    }
    CompositionLocalProvider(LocalDiacritica provides tokens, LocalDensity provides scaled, LocalReadingHanScale provides hanScale, LocalHanFont provides tokens.fonts.han) {
        MaterialTheme(colorScheme = tokens.materialScheme(), typography = typography, content = content)
    }
}
