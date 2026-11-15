package app.diacritica.review

import app.diacritica.data.DictionaryRepository
import app.diacritica.data.Lang
import app.diacritica.data.ScriptPreference
import app.diacritica.data.WordInfo
import app.diacritica.data.user.Card
import app.diacritica.data.user.CardKind
import app.diacritica.data.user.ReviewLog
import app.diacritica.data.user.UserDatabase
import java.util.Calendar

/** Everything a review screen needs for one card. */
data class ReviewItem(
    val card: Card,
    val kind: CardKind,
    val lang: Lang,
    /** The word itself, with article / pinyin etc. (null for SENTENCE cards) */
    val word: WordInfo?,
    /** The same meaning in the other active languages. */
    val translations: List<WordInfo>,
    /** English meaning label of the selected sense, e.g. "device used to open a lock". */
    val meaning: String?,
)

class ReviewRepository(
    private val db: UserDatabase,
    private val dictionary: DictionaryRepository,
) {
    private val cards = db.cards()

    /** Creates the cards that fit a saved word (missing ones only). */
    suspend fun ensureCards(lang: Lang, word: String, active: List<Lang>, script: ScriptPreference) {
        val info = runCatching { dictionary.lookup(word, lang, active, script) }.getOrNull()
        val article = info?.headword?.article
        val kinds = buildList {
            add(CardKind.RECOGNITION)
            if (lang != Lang.EN) add(CardKind.RECALL)
            if (lang != Lang.EN) add(CardKind.LISTENING)
            if (lang == Lang.ZH) add(CardKind.PINYIN)
            if (article != null && lang in setOf(Lang.DE, Lang.FR, Lang.IT, Lang.ES) && !article.endsWith("'")) add(CardKind.GENDER)
        }
        val existing = cards.kindsFor(lang.code, word).toSet()
        val now = System.currentTimeMillis()
        kinds.filter { it.name !in existing }.forEachIndexed { i, k ->
            cards.insert(Card(lang = lang.code, word = word, kind = k.name, createdAt = now + i))
        }
    }

    suspend fun removeWordCards(lang: Lang, word: String) = cards.deleteWordCards(lang.code, word)

    /** Creates cards for saved words that have none yet (words saved before Review existed). */
    suspend fun syncSavedWords(active: List<Lang>, script: ScriptPreference) {
        for (s in db.saved().allNow()) {
            val lang = Lang.fromCode(s.lang) ?: continue
            if (cards.kindsFor(s.lang, s.word).isEmpty()) ensureCards(lang, s.word, active, script)
        }
    }

    /**
     * Creates cards for words looked up in the Dictionary since [since]. The search text is resolved to
     * the dictionary headword first ("schlussel" → Schlüssel, "chiavi" → chiave), so cards use real words.
     * Returns the timestamp of the newest history item processed.
     */
    suspend fun syncHistory(since: Long, active: List<Lang>, script: ScriptPreference): Long {
        var newest = since
        for (h in db.history().since(since)) {
            newest = maxOf(newest, h.at)
            val lang = Lang.fromCode(h.lang) ?: continue
            val r = runCatching { dictionary.lookup(h.query, lang, active, script) }.getOrNull() ?: continue
            if (r.concepts.isEmpty()) continue
            val word = r.headword?.word ?: continue
            if (cards.kindsFor(r.source.code, word).isEmpty()) ensureCards(r.source, word, active, script)
        }
        return newest
    }

    /** A SENTENCE card from Rehearse: prompt → corrected answer. */
    suspend fun addSentenceCard(lang: Lang, prompt: String, answer: String) {
        cards.insert(Card(lang = lang.code, word = "sentence:" + answer.hashCode() + ":" + prompt.hashCode(), kind = CardKind.SENTENCE.name, front = prompt, back = answer))
    }

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Due cards, plus new ones up to today's remaining allowance. */
    suspend fun queue(newPerDay: Int): List<Card> {
        val introduced = cards.newIntroducedSince(startOfToday())
        return cards.dueNow(System.currentTimeMillis(), (newPerDay - introduced).coerceAtLeast(0))
    }

    suspend fun load(card: Card, active: List<Lang>, script: ScriptPreference): ReviewItem {
        val lang = Lang.fromCode(card.lang) ?: Lang.EN
        val kind = runCatching { CardKind.valueOf(card.kind) }.getOrDefault(CardKind.RECOGNITION)
        if (kind == CardKind.SENTENCE) return ReviewItem(card, kind, lang, null, emptyList(), null)
        val r = runCatching { dictionary.lookup(card.word, lang, active, script) }.getOrNull()
        val word = r?.headword ?: WordInfo(lang, card.word)
        val translations = r?.results?.mapNotNull { it.words.firstOrNull() } ?: emptyList()
        val meaning = r?.concepts?.getOrNull(r.selected)?.label?.ifBlank { null }
        return ReviewItem(card, kind, lang, word, translations, meaning)
    }

    suspend fun rate(card: Card, rating: Fsrs.Rating): Card {
        val now = System.currentTimeMillis()
        val result = Fsrs.review(card, rating, now)
        cards.update(result.card)
        cards.log(ReviewLog(cardId = card.id, at = now, rating = rating.value, scheduledDays = result.scheduledDays))
        return result.card
    }

    /** Consecutive days (ending today, or yesterday if today has no review yet) with at least one review. */
    fun streak(reviewTimes: List<Long>): Int {
        val cal = Calendar.getInstance()
        fun dayKey(t: Long): Int { cal.timeInMillis = t; return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) }
        val days = reviewTimes.map(::dayKey).toSet()
        if (days.isEmpty()) return 0
        val walk = Calendar.getInstance()
        if (dayKey(walk.timeInMillis) !in days) walk.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (dayKey(walk.timeInMillis) in days) {
            n++
            walk.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }
}
