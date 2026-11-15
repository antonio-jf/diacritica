package app.diacritica.reader

import app.diacritica.data.Lang
import java.net.URLEncoder

/**
 * Reference dictionaries opened in the browser from the word popup (online only).
 * None offers a free API, so the popup itself always uses the offline data.
 */
object OfficialDictionary {
    fun name(lang: Lang): String = when (lang) {
        Lang.EN -> "Oxford Learner's"
        Lang.ES -> "RAE"
        Lang.FR -> "Larousse"
        Lang.DE -> "Duden"
        Lang.IT -> "Treccani"
        Lang.ZH -> "MOE 國語辭典"
    }

    /** [traditional] is used for Mandarin: Taiwan's Ministry of Education dictionary is in traditional characters. */
    fun url(lang: Lang, word: String, traditional: String? = null): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val w = word.trim()
        return when (lang) {
            Lang.EN -> "https://www.oxfordlearnersdictionaries.com/search/english/?q=${enc(w)}"
            Lang.ES -> "https://dle.rae.es/${enc(w.lowercase())}"
            Lang.FR -> "https://www.larousse.fr/dictionnaires/francais/${enc(w.lowercase())}"
            Lang.DE -> "https://www.duden.de/suchen/dudenonline/${enc(w)}"
            Lang.IT -> "https://www.treccani.it/vocabolario/${enc(w.lowercase())}/"
            Lang.ZH -> "https://dict.revised.moe.edu.tw/search.jsp?md=1&word=${enc(traditional ?: w)}"
        }
    }
}
