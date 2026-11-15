package app.diacritica.data

/**
 * Definite articles shown in front of nouns (la clé, der Schlüssel, lo zaino).
 * Gender strings come from the database: "m", "f", "n", "m/f", optionally followed by " pl".
 */
object Articles {
    private val spanishStressedA = setOf(
        "agua", "águila", "alma", "arma", "ala", "ave", "aula", "área", "arca", "asa", "aura",
        "hambre", "hacha", "hada", "habla", "haba", "hampa", "álgebra", "ancla", "ansia", "arpa",
    )
    private val vowelStart =Regex("^[aeiouyàâäéèêëîïôöùûüœæhAEIOUYÀÂÄÉÈÊËÎÏÔÖÙÛÜŒÆH]")

    fun forNoun(lang: Lang, word: String, gender: String?, pos: String?): String? {
        if (gender.isNullOrBlank()) return null
        if (pos != null && pos != "noun") return null
        val plural = gender.endsWith(" pl")
        val g = gender.removeSuffix(" pl").split('/').first()
        return when (lang) {
            Lang.DE -> if (plural) "die" else when (g) { "m" -> "der"; "f" -> "die"; "n" -> "das"; else -> null }
            Lang.FR -> when {
                plural -> "les"
                // Aspirated h (le héros) is not detectable from spelling; elision is shown for vowels and h.
                vowelStart.containsMatchIn(word) -> "l'"
                g == "m" -> "le"
                g == "f" -> "la"
                else -> null
            }
            Lang.ES -> when {
                plural && g == "f" -> "las"
                plural -> "los"
                g == "m" -> "el"
                // Feminine nouns starting with a stressed a/ha take "el" in the singular (el agua).
                g == "f" && word.lowercase() in spanishStressedA -> "el"
                g == "f" -> "la"
                else -> null
            }
            Lang.IT -> italian(word, g, plural)
            else -> null
        }
    }

    private fun italian(word: String, g: String, plural: Boolean): String? {
        val w = word.lowercase()
        val vowel = w.firstOrNull()?.let { it in "aeiouàèéìòù" } == true
        val impure = w.startsWith("s") && w.length > 1 && w[1] !in "aeiouàèéìòù" ||
            w.startsWith("z") || w.startsWith("gn") || w.startsWith("ps") || w.startsWith("pn") ||
            w.startsWith("x") || w.startsWith("y")
        return when (g) {
            "m" -> when {
                plural && (vowel || impure) -> "gli"
                plural -> "i"
                vowel -> "l'"
                impure -> "lo"
                else -> "il"
            }
            "f" -> when {
                plural -> "le"
                vowel -> "l'"
                else -> "la"
            }
            else -> null
        }
    }

    fun genderLabel(gender: String?): String? = gender?.let {
        val base = it.removeSuffix(" pl").split('/').joinToString(" / ") { g ->
            when (g) { "m" -> "masculine"; "f" -> "feminine"; "n" -> "neuter"; "c" -> "common"; else -> g }
        }
        if (it.endsWith(" pl")) "$base plural" else base
    }

    fun genderShort(gender: String?): String? = gender?.replace("/", " / ")
}
