package app.diacritica.data

/**
 * Pinyin split into syllables, each with its tone (1–4, 5 = neutral). Works on spaced ("zhōng guó"),
 * joined ("zhōngguó", "diànshì", "dàngāo") and numbered ("zhong1 guo2") pinyin. Characters outside a
 * syllable (spaces, apostrophes, punctuation) come back with tone 0.
 */
object PinyinTones {
    private const val T1 = "āēīōūǖĀĒĪŌŪǕ"
    private const val T2 = "áéíóúǘÁÉÍÓÚǗ"
    private const val T3 = "ǎěǐǒǔǚǍĚǏǑǓǙ"
    private const val T4 = "àèìòùǜÀÈÌÒÙǛ"

    fun toneOf(ch: Char): Int = when (ch) {
        in T1 -> 1; in T2 -> 2; in T3 -> 3; in T4 -> 4; else -> 0
    }

    private fun isVowel(ch: Char): Boolean = ch.lowercaseChar() in "aeiouüv" || toneOf(ch) != 0
    private fun isLetter(ch: Char): Boolean = ch.isLetter()

    data class Piece(val text: String, val tone: Int)

    fun split(s: String): List<Piece> {
        val out = ArrayList<Piece>()
        var i = 0
        while (i < s.length) {
            if (!isLetter(s[i])) {
                // Separators; a digit right after a syllable was already taken as its tone.
                val start = i
                while (i < s.length && !isLetter(s[i])) i++
                out += Piece(s.substring(start, i), 0)
                continue
            }
            // A run of letters: one or more syllables.
            val start = i
            while (i < s.length && isLetter(s[i])) i++
            var end = i
            var digitTone = 0
            if (end < s.length && s[end] in '1'..'5') { digitTone = s[end] - '0'; end++; i = end }
            out += word(s.substring(start, end), digitTone)
        }
        return out
    }

    /** One letter run ("diànshì") cut at the consonants between vowel groups. */
    private fun word(w: String, digitTone: Int): List<Piece> {
        val letters = if (digitTone != 0) w.dropLast(1) else w
        // Vowel groups [start, end).
        val groups = ArrayList<IntArray>()
        var i = 0
        while (i < letters.length) {
            if (isVowel(letters[i])) {
                val s0 = i
                while (i < letters.length && isVowel(letters[i])) i++
                groups += intArrayOf(s0, i)
            } else i++
        }
        if (groups.isEmpty()) return listOf(Piece(w, 0)) // "m", "hm", "ng": no tone to show
        // Boundaries between syllables: decide where the consonants between two vowel groups go.
        val cuts = ArrayList<Int>()
        for (g in 0 until groups.size - 1) {
            val a = groups[g][1]; val b = groups[g + 1][0]
            val run = letters.substring(a, b).lowercase()
            val keep = when {
                run.isEmpty() -> 0                       // "xīān" without apostrophe: cut between groups
                run.startsWith("ng") && run.length >= 3 -> 2 // zhōng|guó
                (run.startsWith("n") || run.startsWith("r")) && run.length >= 2 -> 1 // diàn|shì, ér|zi, dàn|gāo
                else -> 0                                 // má|ma, xué|xí
            }
            cuts += a + keep
        }
        val out = ArrayList<Piece>()
        var from = 0
        for ((k, cut) in (cuts + letters.length).withIndex()) {
            val syl = letters.substring(from, cut)
            val marked = syl.map { toneOf(it) }.firstOrNull { it != 0 }
            val tone = marked ?: if (k == groups.size - 1 && digitTone != 0) digitTone else 5
            out += Piece(syl, tone)
            from = cut
        }
        if (digitTone != 0) out += Piece(digitTone.toString(), 0)
        return out
    }
}
