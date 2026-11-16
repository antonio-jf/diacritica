package app.diacritica.reader

import app.diacritica.data.Lang
import app.diacritica.data.user.Story
import app.diacritica.data.user.StorySentence
import app.diacritica.data.user.UserDatabase
import app.diacritica.rehearse.GeminiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A story ready to read: titles and aligned sentences per language. */
data class LoadedStory(val story: Story, val titles: Map<Lang, String>, val sentences: List<Map<Lang, String>>) {
    val languages: List<Lang> get() = Lang.entries.filter { l -> sentences.firstOrNull()?.containsKey(l) == true }
    fun title(l: Lang): String = titles[l] ?: titles[Lang.EN] ?: titles.values.firstOrNull() ?: "Story"
}

class StoryRepository(private val db: UserDatabase, private val gemini: GeminiClient) {
    private val dao = db.stories()
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): Flow<List<Story>> = dao.all()

    fun titles(story: Story): Map<Lang, String> = runCatching {
        (json.parseToJsonElement(story.titles) as JsonObject).mapNotNull { (k, v) ->
            Lang.fromCode(k)?.let { it to ((v as? JsonPrimitive)?.content ?: "") }
        }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun load(id: Long): LoadedStory? {
        val s = dao.byId(id) ?: return null
        val rows = dao.sentences(id)
        val byIdx = rows.groupBy { it.idx }.toSortedMap().values.map { list ->
            list.mapNotNull { r -> Lang.fromCode(r.lang)?.let { it to r.text } }.toMap()
        }
        return LoadedStory(s, titles(s), byIdx)
    }

    suspend fun saveProgress(id: Long, idx: Int) = dao.setProgress(id, idx)

    suspend fun delete(id: Long) { dao.deleteSentences(id); dao.deleteStory(id) }

    private suspend fun store(titles: Map<Lang, String>, sentences: List<Map<Lang, String>>, level: String, topic: String?, source: String): Long {
        val t = buildJsonObject { titles.forEach { (l, v) -> put(l.code, v) } }.toString()
        val id = dao.insert(Story(titles = t, level = level, topic = topic, source = source, sentenceCount = sentences.size))
        dao.insertSentences(sentences.flatMapIndexed { i, m -> m.map { (l, text) -> StorySentence(id, i, l.code, text) } })
        return id
    }

    /** A story from your own pasted text, already split and translated. */
    suspend fun importText(title: String, langs: List<Lang>, sentences: List<Map<Lang, String>>): Long =
        store(langs.associateWith { title }, sentences, "Your text", null, "text:${System.currentTimeMillis()}")

    /** Puts a Classic on your shelf (once) so it reads, saves progress and feeds Rehearse like any story. */
    suspend fun importBook(book: BookInfo, sentences: List<Map<Lang, String>>): Long {
        val source = "book:${book.id}"
        dao.idBySource(source)?.let { return it }
        return store(mapOf(book.a to book.title, book.b to book.title), sentences, "Classic", book.author, source)
    }

    /** Adds the hand-written starter story the first time the Reader opens. */
    suspend fun ensureStarter() {
        if (dao.count() > 0) return
        store(StarterStory.titles, StarterStory.sentences, "B1 · HSK 3", "A fisherman's morning", "starter")
    }

    /** Writes a new story with Gemini (all six languages, aligned) and stores it. Returns its id. */
    suspend fun generate(apiKey: String, model: String, topic: String, levelEu: String, hsk: Int): Long {
        val langs = Lang.entries.toList()
        val draft = gemini.generateStory(apiKey, model, topic, levelEu, hsk, langs)
        return store(draft.titles, draft.sentences, "$levelEu · HSK $hsk", topic, "gemini")
    }
}

/** A short original story, written for this app, in all six languages (B1 / HSK 3). */
object StarterStory {
    val titles = mapOf(
        Lang.EN to "The Old Fisherman", Lang.FR to "Le vieux pêcheur", Lang.DE to "Der alte Fischer",
        Lang.IT to "Il vecchio pescatore", Lang.ES to "El viejo pescador", Lang.ZH to "老渔夫",
    )

    private fun s(en: String, fr: String, de: String, it: String, es: String, zh: String) =
        mapOf(Lang.EN to en, Lang.FR to fr, Lang.DE to de, Lang.IT to it, Lang.ES to es, Lang.ZH to zh)

    val sentences = listOf(
        s("The old fisherman opened the door of his hut.", "Le vieux pêcheur a ouvert la porte de sa cabane.",
            "Der alte Fischer öffnete die Tür seiner Hütte.", "Il vecchio pescatore ha aperto la porta della sua capanna.",
            "El viejo pescador abrió la puerta de su cabaña.", "老渔夫打开了他小屋的门。"),
        s("Outside, the sea was calm and grey.", "Dehors, la mer était calme et grise.",
            "Draußen war das Meer ruhig und grau.", "Fuori il mare era calmo e grigio.",
            "Afuera, el mar estaba tranquilo y gris.", "外面，大海很平静，灰蒙蒙的。"),
        s("He took his net and greeted the cat.", "Il a pris son filet et a salué le chat.",
            "Er nahm sein Netz und grüßte die Katze.", "Ha preso la rete e ha salutato il gatto.",
            "Tomó su red y saludó al gato.", "他拿起渔网，跟猫打了个招呼。"),
        s("Then he walked slowly down to the harbour.", "Puis il est descendu lentement jusqu'au port.",
            "Dann ging er langsam zum Hafen hinunter.", "Poi è sceso lentamente fino al porto.",
            "Luego bajó despacio hasta el puerto.", "然后他慢慢地走到港口。"),
        s("His boat was small, but it was strong.", "Son bateau était petit, mais il était solide.",
            "Sein Boot war klein, aber es war stabil.", "La sua barca era piccola, ma era robusta.",
            "Su barca era pequeña, pero era fuerte.", "他的船很小，但是很结实。"),
        s("All morning he waited, and no fish came.", "Il a attendu toute la matinée, et aucun poisson n'est venu.",
            "Den ganzen Vormittag wartete er, und kein Fisch kam.", "Ha aspettato tutta la mattina, e non è arrivato nessun pesce.",
            "Esperó toda la mañana, y no llegó ningún pez.", "他等了一个上午，一条鱼也没有来。"),
        s("At noon, a little girl sat down next to him.", "À midi, une petite fille s'est assise à côté de lui.",
            "Am Mittag setzte sich ein kleines Mädchen neben ihn.", "A mezzogiorno, una bambina si è seduta accanto a lui.",
            "Al mediodía, una niña se sentó a su lado.", "中午，一个小女孩坐到了他旁边。"),
        s("“Fish are like words,” he told her. “You need patience to catch them.”",
            "« Les poissons sont comme les mots, lui a-t-il dit. Il faut de la patience pour les attraper. »",
            "„Fische sind wie Wörter“, sagte er zu ihr. „Man braucht Geduld, um sie zu fangen.“",
            "«I pesci sono come le parole» le ha detto. «Ci vuole pazienza per prenderli.»",
            "—Los peces son como las palabras —le dijo—. Hace falta paciencia para atraparlos.",
            "他对她说：“鱼就像词语一样，要有耐心才能抓到。”"),
    )
}
