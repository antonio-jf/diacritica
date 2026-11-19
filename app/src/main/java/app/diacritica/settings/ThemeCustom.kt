package app.diacritica.settings

/**
 * Theme builder. A preset ([ThemeId]) is the starting point; each part below can be swapped on its own.
 * A null part means "the preset's own".
 */
enum class FontChoice(val label: String) {
    SERIF("Source Serif"), INTER("Inter"), JAKARTA("Jakarta"), PLEX("IBM Plex"), GROTESK("Space Grotesk"), SYSTEM("System"),
}

/** Font for Chinese characters. Serif uses the phone's Song-style font (Noto Serif CJK) when it has one. */
enum class HanFont(val label: String) { SANS("Sans · 黑体"), SERIF("Serif · 宋体") }

/** Background and color family, each with light and dark versions and its own accents. */
enum class PaletteId(val label: String) {
    CREAM("Cream"), SAGE("Sage"), SLATE("Slate"), WHITE("White"), STONE("Stone"), ROSE("Rose"),
}

enum class CornerChoice(val label: String) { SHARP("Sharp"), SOFT("Soft"), ROUND("Round") }

/** How grouped content is set apart. */
enum class SurfaceChoice(val label: String) { LINES("Thin lines"), CARDS("Soft cards"), OUTLINED("Outlined"), BOLD("Bold rules") }

enum class DensityChoice(val label: String) { COMPACT("Compact"), COMFORTABLE("Comfortable"), ROOMY("Roomy") }

data class ThemeCustom(
    val display: FontChoice? = null,
    val body: FontChoice? = null,
    val han: HanFont? = null,
    val palette: PaletteId? = null,
    val corners: CornerChoice? = null,
    val surface: SurfaceChoice? = null,
    val density: DensityChoice? = null,
) {
    val isEmpty: Boolean get() = this == ThemeCustom()

    /** "display=SERIF;palette=STONE" — only the parts that are set. */
    fun encode(): String = listOfNotNull(
        display?.let { "display=${it.name}" }, body?.let { "body=${it.name}" }, han?.let { "han=${it.name}" },
        palette?.let { "palette=${it.name}" }, corners?.let { "corners=${it.name}" },
        surface?.let { "surface=${it.name}" }, density?.let { "density=${it.name}" },
    ).joinToString(";")

    companion object {
        fun decode(s: String?): ThemeCustom {
            if (s.isNullOrBlank()) return ThemeCustom()
            val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            fun <E : Enum<E>> pick(values: Array<E>, key: String): E? = m[key]?.let { v -> values.firstOrNull { it.name == v } }
            return ThemeCustom(
                display = pick(FontChoice.entries.toTypedArray(), "display"),
                body = pick(FontChoice.entries.toTypedArray(), "body"),
                han = pick(HanFont.entries.toTypedArray(), "han"),
                palette = pick(PaletteId.entries.toTypedArray(), "palette"),
                corners = pick(CornerChoice.entries.toTypedArray(), "corners"),
                surface = pick(SurfaceChoice.entries.toTypedArray(), "surface"),
                density = pick(DensityChoice.entries.toTypedArray(), "density"),
            )
        }
    }
}

/** A theme the user saved: a preset plus their changes and accent. */
data class SavedTheme(val name: String, val base: ThemeId, val accent: AccentChoice, val custom: ThemeCustom) {
    companion object {
        /** One theme per line: name|base|accent|parts. */
        fun encodeAll(list: List<SavedTheme>): String = list.joinToString("\n") { t ->
            listOf(clean(t.name), t.base.name, t.accent.name, t.custom.encode()).joinToString("|")
        }

        fun decodeAll(s: String?): List<SavedTheme> = s.orEmpty().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 4 || p[0].isBlank()) return@mapNotNull null
            SavedTheme(
                name = p[0],
                base = ThemeId.entries.firstOrNull { it.name == p[1] } ?: ThemeId.PAPER,
                accent = AccentChoice.entries.firstOrNull { it.name == p[2] } ?: AccentChoice.THEME,
                custom = ThemeCustom.decode(p[3]),
            )
        }

        fun clean(name: String): String = name.replace(Regex("[|\\n\\r]"), " ").trim().take(30)
    }
}
