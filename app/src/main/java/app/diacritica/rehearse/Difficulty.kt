package app.diacritica.rehearse

/**
 * Rehearse difficulty on a 1–10 scale instead of CEFR (A2/B1…) or HSK labels, so Mandarin and the European
 * languages use the same words. Each step maps to:
 * - for the Tatoeba bank: a window on the rarest word's frequency rank and the sentence length in words;
 * - for the News/Formal/Encyclopedic corpora (no rank column): their CEFR tags;
 * - for Gemini: a CEFR level (European languages) and an HSK level (Mandarin) to write at.
 */
object Difficulty {
    const val MIN = 1
    const val MAX = 10

    data class Band(
        val step: Int,
        /** Frequency rank of the rarest word (English anchor): lowest and highest allowed. */
        val minRank: Int, val maxRank: Int,
        val minWords: Int, val maxWords: Int,
        /** CEFR tags used where there's no rank (corpora) and as a fallback. */
        val cefr: Set<String>,
        /** What Gemini is told: CEFR for European languages… */
        val cefrLabel: String,
        /** …and HSK for Mandarin. */
        val hsk: Int,
        val description: String,
    )

    private val bands = listOf(
        Band(1, 0, 600, 3, 6, setOf("A2"), "A1", 1, "Very short, simple sentences"),
        Band(2, 0, 1_000, 3, 7, setOf("A2"), "A1–A2", 1, "Simple everyday phrases"),
        Band(3, 0, 1_500, 4, 8, setOf("A2"), "A2", 2, "Basic everyday topics"),
        Band(4, 500, 2_000, 4, 10, setOf("A2", "B1"), "A2–B1", 2, "Familiar topics, a little longer"),
        Band(5, 1_000, 3_500, 5, 11, setOf("B1"), "B1", 3, "Everyday topics, common vocabulary"),
        Band(6, 2_000, 5_000, 6, 13, setOf("B1"), "B1–B2", 3, "Wider vocabulary, some nuance"),
        Band(7, 3_000, 8_000, 7, 15, setOf("B2"), "B2", 4, "Varied topics, longer sentences"),
        Band(8, 5_000, 12_000, 8, 18, setOf("B2"), "B2+", 5, "Less common words, complex sentences"),
        Band(9, 8_000, Int.MAX_VALUE, 9, 25, setOf("B2", "C1"), "B2–C1", 5, "Advanced and abstract topics"),
        Band(10, 12_000, Int.MAX_VALUE, 10, 40, setOf("C1"), "C1", 6, "Near-native, rich vocabulary"),
    )

    fun band(step: Int): Band = bands[step.coerceIn(MIN, MAX) - 1]

    /** Rough CEFR / HSK equivalent, shown small under the slider. */
    fun cefrHint(step: Int) = "≈ " + band(step).cefrLabel
    fun hskHint(step: Int) = "≈ HSK " + band(step).hsk

    /** Stored level of a sentence written at a given step ("L7"). */
    fun tag(step: Int) = "L" + step.coerceIn(MIN, MAX)

    /** "Level 7" for display, from a step tag or a CEFR tag (bank sentences, stories). */
    fun label(level: String): String {
        level.removePrefix("L").toIntOrNull()?.takeIf { level.startsWith("L") }?.let { return "Level $it" }
        val step = mapOf("A1" to 1, "A2" to 3, "B1" to 5, "B2" to 7, "C1" to 9, "C2" to 10)[level.take(2)] ?: return level
        return "Level $step"
    }

    /** Old settings (A2/B1/B2 chips) → a step, for people upgrading. */
    fun fromCefr(levels: Set<String>): Int {
        val steps = levels.mapNotNull { mapOf("A2" to 3, "B1" to 5, "B2" to 7, "C1" to 10)[it] }
        return if (steps.isEmpty()) 5 else steps.average().let { Math.round(it).toInt() }.coerceIn(MIN, MAX)
    }

    /** Old Story HSK setting → a Mandarin step. */
    fun fromHsk(hsk: Int): Int = mapOf(1 to 2, 2 to 3, 3 to 5, 4 to 7, 5 to 8, 6 to 10)[hsk] ?: 3
}
