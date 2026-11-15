package app.diacritica.data

import java.text.Normalizer

/**
 * Search-key normalization. Must stay identical to `normalize()` in pipeline/build_dictionary.py,
 * because the database stores keys produced by the Python version.
 */
object TextNorm {
    private val special = mapOf(
        'ß' to "ss", 'œ' to "oe", 'æ' to "ae", 'ø' to "o", 'ł' to "l",
        'đ' to "d", 'ı' to "i", '’' to "'", 'ʼ' to "'",
    )
    private val combining = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")
    private val han = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val pinyinNumbered = Regex("^[a-züv:]+[1-5](\\s*[a-züv:]+[1-5]?)*$")
    private val pinyinLetters = Regex("^[a-zA-Züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ' ]+$")

    fun normalize(input: String): String {
        val lowered = input.lowercase()
        val sb = StringBuilder(lowered.length)
        for (c in lowered) sb.append(special[c] ?: c)
        val decomposed = Normalizer.normalize(sb, Normalizer.Form.NFD)
        return combining.replace(decomposed, "").trim().replace(spaces, " ")
    }

    fun containsHan(s: String): Boolean = han.containsMatchIn(s)

    /** True for "ni3hao3", "yao4 shi", "lv3". */
    fun looksLikeNumberedPinyin(s: String): Boolean = pinyinNumbered.matches(s.trim().lowercase())

    fun looksLikePinyin(s: String): Boolean = pinyinLetters.matches(s.trim())

    /** Key for zh.pinyin_num: lowercase, ü/v/u: → u, no spaces. "Lv3 xing2" → "lu3xing2". */
    fun pinyinNumKey(s: String): String =
        s.lowercase().replace("u:", "u").replace('ü', 'u').replace('v', 'u').replace(" ", "")

    private val toneMarks = Regex("[āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜĀÁǍÀĒÉĚÈĪÍǏÌŌÓǑÒŪÚǓÙǕǗǙǛ]")

    fun hasToneMarks(s: String): Boolean = toneMarks.containsMatchIn(s)

    /** Tone-marked pinyin as stored in zh.pinyin, lowercased without spaces/apostrophes: "Lǎo shī" → "lǎoshī". */
    fun pinyinMarkedKey(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFC).replace(" ", "").replace("'", "").replace("’", "")

    /** Key for zh.pinyin_norm: toneless, no spaces, no apostrophes. "Xī'ān" → "xian". */
    fun pinyinToneless(s: String): String =
        normalize(s).replace('v', 'u').replace("'", "").replace(" ", "").replace(Regex("[1-5]"), "")
}
