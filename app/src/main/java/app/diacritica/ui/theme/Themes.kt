package app.diacritica.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang
import app.diacritica.settings.ThemeId

/** Static definition of one theme: both color modes, accent alternatives, fonts, shapes. */
data class ThemeSpec(
    val id: ThemeId,
    val light: PolyColors,
    val dark: PolyColors,
    /** Two alternative accents (light, dark) for AccentChoice.ALT1 / ALT2. */
    val altAccents: List<Pair<Color, Color>>,
    val fonts: PolyFonts,
    val type: PolyType,
    val shapes: PolyShapes,
    val spacing: PolySpacing,
    val surfaceStyle: SurfaceStyle,
    val heavyRules: Boolean,
    /** Midnight is a dark-first theme: "Follow system" still works, this only sets the preview. */
    val previewDark: Boolean = false,
)

private fun c(hex: Long) = Color(hex or 0xFF000000)

private fun colors(
    dark: Boolean, ground: Long, surface: Long, surfaceAlt: Long, ink: Long, inkMuted: Long, line: Long,
    accent: Long, onAccent: Long, accentSoft: Long, onAccentSoft: Long, success: Long, error: Long, warning: Long,
    rule: Long = line,
) = PolyColors(
    ground = c(ground), surface = c(surface), surfaceAlt = c(surfaceAlt), ink = c(ink), inkMuted = c(inkMuted),
    line = c(line), rule = c(rule), accent = c(accent), onAccent = c(onAccent), accentSoft = c(accentSoft),
    onAccentSoft = c(onAccentSoft), success = c(success), error = c(error), warning = c(warning), isDark = dark,
)

object Themes {
    val paper = ThemeSpec(
        id = ThemeId.PAPER,
        light = colors(false, 0xFAF8F3, 0xFFFFFF, 0xF1EDE4, 0x1C1B19, 0x5E5A52, 0xE4DFD5,
            0x2B4FA8, 0xFFFFFF, 0xE3EAF8, 0x1E3A80, 0x2E7D4F, 0xB3261E, 0x8A5A00),
        dark = colors(true, 0x1B1A18, 0x24221F, 0x2C2A26, 0xEDE8DF, 0xABA498, 0x3A3731,
            0x9DB4EE, 0x10172B, 0x2A3350, 0xC9D6F7, 0x6FCF97, 0xF2B8B5, 0xE2B65C),
        altAccents = listOf(c(0x8C3B2E) to c(0xE5A397), c(0x2F6B4F) to c(0x8FD1B0)),
        fonts = PolyFonts(display = Fonts.sourceSerif, body = Fonts.inter, label = Fonts.inter, reading = Fonts.sourceSerif),
        type = PolyType(title = 26.sp, headword = 21.sp, capsLabels = true, exampleItalic = true, titleWeight = 600, headwordWeight = 500),
        // Soft corners and pill controls; the old 2dp corners made everything look boxed.
        shapes = PolyShapes(card = 14.dp, chip = 20.dp, button = 24.dp, field = 12.dp),
        spacing = PolySpacing(screen = 20.dp, row = 14.dp, gap = 10.dp),
        surfaceStyle = SurfaceStyle.HAIRLINE,
        heavyRules = false,
    )

    val tiles = ThemeSpec(
        id = ThemeId.TILES,
        light = colors(false, 0xF3F6F5, 0xFFFFFF, 0xE9EFED, 0x14211F, 0x4E5D5A, 0xDCE4E2,
            0x0F766E, 0xFFFFFF, 0xDDEFEC, 0x0F4F49, 0x0F766E, 0xC2410C, 0x7A5B00),
        dark = colors(true, 0x101716, 0x18211F, 0x1F2B29, 0xE6EFED, 0xA3B3B0, 0x2A3836,
            0x5FD0C2, 0x062B27, 0x17403B, 0xBDEDE6, 0x5FD0C2, 0xFFB59A, 0xE8C46A),
        altAccents = listOf(c(0x6D4BC3) to c(0xB9A3F5), c(0xB02157) to c(0xF59BBD)),
        fonts = PolyFonts(display = Fonts.jakarta, body = Fonts.jakarta, label = Fonts.jakarta, reading = Fonts.jakarta),
        type = PolyType(title = 24.sp, headword = 19.sp, capsLabels = false, exampleItalic = false, titleWeight = 700, headwordWeight = 700),
        shapes = PolyShapes(card = 18.dp, chip = 16.dp, button = 26.dp, field = 28.dp),
        spacing = PolySpacing(screen = 16.dp, row = 14.dp, gap = 10.dp),
        surfaceStyle = SurfaceStyle.TONAL,
        heavyRules = false,
    )

    val midnight = ThemeSpec(
        id = ThemeId.MIDNIGHT,
        light = colors(false, 0xF4F5F7, 0xFFFFFF, 0xEBEDF0, 0x14171B, 0x565D68, 0xD9DDE2,
            0x9A5B00, 0xFFFFFF, 0xFBEFD9, 0x6B4100, 0x1E7A47, 0xB42318, 0x9A5B00),
        dark = colors(true, 0x111316, 0x1A1D21, 0x23272C, 0xECEEF1, 0xA3A9B3, 0x2A2E34,
            0xF2B544, 0x1A1406, 0x2A2412, 0xF7D58A, 0x6FD39A, 0xFF8A7A, 0xF2B544),
        altAccents = listOf(c(0x0B6BA8) to c(0x4FB3F2), c(0x6C3FC4) to c(0xB48CF2)),
        fonts = PolyFonts(display = Fonts.plexSans, body = Fonts.plexSans, label = Fonts.plexMono, reading = Fonts.plexSans),
        type = PolyType(title = 22.sp, headword = 20.sp, capsLabels = true, labelTracking = 0.3.sp, exampleItalic = false, titleWeight = 600, headwordWeight = 600),
        shapes = PolyShapes(card = 12.dp, chip = 20.dp, button = 24.dp, field = 12.dp),
        spacing = PolySpacing(screen = 16.dp, row = 12.dp, gap = 8.dp),
        surfaceStyle = SurfaceStyle.BORDERED,
        heavyRules = false,
        previewDark = true,
    )

    val index = ThemeSpec(
        id = ThemeId.INDEX,
        light = colors(false, 0xFFFFFF, 0xFFFFFF, 0xF5F5F5, 0x0A0A0A, 0x525252, 0xE5E5E5,
            0xC2410C, 0xFFFFFF, 0xFFF7ED, 0x9A3412, 0x15803D, 0xB91C1C, 0xA16207, rule = 0x0A0A0A),
        dark = colors(true, 0x0A0A0A, 0x141414, 0x1C1C1C, 0xF5F5F5, 0xA3A3A3, 0x2E2E2E,
            0xFB7A3C, 0x1A0A00, 0x2B1608, 0xFFC9A8, 0x4ADE80, 0xF87171, 0xFACC15, rule = 0xF5F5F5),
        altAccents = listOf(c(0x1D4ED8) to c(0x7FA2FF), c(0x047857) to c(0x5EE0B0)),
        fonts = PolyFonts(display = Fonts.spaceGrotesk, body = Fonts.inter, label = Fonts.spaceGrotesk, reading = Fonts.inter),
        type = PolyType(title = 30.sp, headword = 18.sp, headwordLarge = 40.sp, capsLabels = true, labelTracking = 0.6.sp, exampleItalic = true, titleWeight = 700, headwordWeight = 600),
        shapes = PolyShapes(card = 0.dp, chip = 0.dp, button = 0.dp, field = 0.dp),
        spacing = PolySpacing(screen = 16.dp, row = 12.dp, gap = 10.dp),
        surfaceStyle = SurfaceStyle.RULED,
        heavyRules = true,
    )

    fun spec(id: ThemeId): ThemeSpec = when (id) {
        ThemeId.PAPER -> paper
        ThemeId.TILES -> tiles
        ThemeId.MIDNIGHT -> midnight
        ThemeId.INDEX -> index
    }

    /** Per-language colors, used everywhere when "Language colors" is on. */
    fun langPalette(dark: Boolean): Map<Lang, LangColor> = if (!dark) mapOf(
        Lang.EN to LangColor(c(0xE6E8EC), c(0x3A4250)),
        Lang.FR to LangColor(c(0xE3E9FB), c(0x2C46B8)),
        Lang.DE to LangColor(c(0xFDE9DC), c(0xA8420A)),
        Lang.IT to LangColor(c(0xDFF3E3), c(0x22713A)),
        Lang.ES to LangColor(c(0xFBE3EC), c(0xA21C4C)),
        Lang.ZH to LangColor(c(0xF6EDCF), c(0x7A5B00)),
    ) else mapOf(
        Lang.EN to LangColor(c(0x2C313A), c(0xC9CED8)),
        Lang.FR to LangColor(c(0x24305A), c(0xBCCBFA)),
        Lang.DE to LangColor(c(0x4A2A16), c(0xFFC7A3)),
        Lang.IT to LangColor(c(0x1C3D25), c(0xA8E5B7)),
        Lang.ES to LangColor(c(0x4A1C2E), c(0xF9B6CE)),
        Lang.ZH to LangColor(c(0x40350F), c(0xF0D88A)),
    )
}
