package app.diacritica.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.diacritica.R

/**
 * Bundled fonts (SIL Open Font License, Latin + Latin Extended subsets, which covers French,
 * German, Italian, Spanish accents and pinyin tone marks). Chinese characters fall back to the
 * system CJK font automatically.
 */
object Fonts {
    val inter = FontFamily(
        Font(R.font.inter_400, FontWeight.Normal),
        Font(R.font.inter_500, FontWeight.Medium),
        Font(R.font.inter_600, FontWeight.SemiBold),
        Font(R.font.inter_600, FontWeight.Bold),
    )
    val sourceSerif = FontFamily(
        Font(R.font.source_serif_400, FontWeight.Normal),
        Font(R.font.source_serif_400, FontWeight.Medium),
        Font(R.font.source_serif_600, FontWeight.SemiBold),
        Font(R.font.source_serif_600, FontWeight.Bold),
    )
    val jakarta = FontFamily(
        Font(R.font.jakarta_400, FontWeight.Normal),
        Font(R.font.jakarta_400, FontWeight.Medium),
        Font(R.font.jakarta_600, FontWeight.SemiBold),
        Font(R.font.jakarta_700, FontWeight.Bold),
    )
    val plexSans = FontFamily(
        Font(R.font.plex_sans_400, FontWeight.Normal),
        Font(R.font.plex_sans_500, FontWeight.Medium),
        Font(R.font.plex_sans_600, FontWeight.SemiBold),
        Font(R.font.plex_sans_600, FontWeight.Bold),
    )
    val plexMono = FontFamily(Font(R.font.plex_mono_500, FontWeight.Normal), Font(R.font.plex_mono_500, FontWeight.Medium))
    val spaceGrotesk = FontFamily(
        Font(R.font.space_grotesk_500, FontWeight.Normal),
        Font(R.font.space_grotesk_500, FontWeight.Medium),
        Font(R.font.space_grotesk_700, FontWeight.SemiBold),
        Font(R.font.space_grotesk_700, FontWeight.Bold),
    )
}
