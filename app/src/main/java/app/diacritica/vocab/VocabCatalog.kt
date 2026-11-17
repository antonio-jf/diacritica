package app.diacritica.vocab

import app.diacritica.data.Lang
import app.diacritica.rehearse.GeminiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** A topic you can add to Vocabulary: Gemini writes its words once, then it's kept on the phone. */
data class CatalogTopic(val id: String, val title: String, val category: String, val hint: String)

object VocabCatalog {
    val topics: List<CatalogTopic> = listOf(
        // Nature and science
        CatalogTopic("plants", "Plants and gardening", "Nature and science", "plant parts, garden plants, gardening tools and tasks"),
        CatalogTopic("trees-flowers", "Trees and flowers", "Nature and science", "common trees and flowers"),
        CatalogTopic("insects", "Insects and small creatures", "Nature and science", "insects, spiders, worms, snails"),
        CatalogTopic("birds", "Birds", "Nature and science", "common birds and bird-related words"),
        CatalogTopic("sea-life", "Sea life", "Nature and science", "fish, sea animals, the coast"),
        CatalogTopic("landscape", "Landscape and geography", "Nature and science", "valleys, coasts, deserts, compass points"),
        CatalogTopic("weather-plus", "Weather in depth", "Nature and science", "forecast words, temperature, extreme weather"),
        CatalogTopic("astronomy", "Space and astronomy", "Nature and science", "planets, stars, orbit, telescope"),
        CatalogTopic("science", "Science basics", "Nature and science", "experiment, hypothesis, laboratory, measurement"),
        CatalogTopic("chemistry", "Chemistry", "Nature and science", "elements, reactions, acids, molecules"),
        CatalogTopic("physics", "Physics", "Nature and science", "force, energy, speed, gravity, waves"),
        CatalogTopic("maths", "Mathematics", "Nature and science", "numbers, operations, shapes, equations"),
        CatalogTopic("environment", "Environment and climate", "Nature and science", "pollution, recycling, renewable energy"),
        // Technology and work
        CatalogTopic("engineering", "Engineering", "Technology and work", "design, structures, machines, materials"),
        CatalogTopic("computers", "Computers and the internet", "Technology and work", "hardware, software, network, password"),
        CatalogTopic("programming", "Programming", "Technology and work", "code, bug, function, database, server"),
        CatalogTopic("tools", "Tools and DIY", "Technology and work", "hammer, screwdriver, drill, paint"),
        CatalogTopic("cars", "Cars and driving", "Technology and work", "car parts, driving, breakdowns"),
        CatalogTopic("materials", "Materials and shapes", "Technology and work", "wood, metal, glass, round, square"),
        CatalogTopic("business", "Business and finance", "Technology and work", "company, profit, invoice, investment"),
        CatalogTopic("office", "The office and meetings", "Technology and work", "agenda, deadline, report, presentation"),
        CatalogTopic("job-search", "Looking for a job", "Technology and work", "CV, interview, contract, experience"),
        CatalogTopic("law", "Law and justice", "Technology and work", "court, judge, crime, rights"),
        // Society and culture
        CatalogTopic("politics", "Politics and government", "Society and culture", "election, parliament, law, citizen"),
        CatalogTopic("media", "News and media", "Society and culture", "newspaper, headline, interview, broadcast"),
        CatalogTopic("music", "Music", "Society and culture", "instruments, genres, concert, song"),
        CatalogTopic("art", "Art and museums", "Society and culture", "painting, sculpture, exhibition, artist"),
        CatalogTopic("architecture", "Architecture and buildings", "Society and culture", "building parts, styles, construction"),
        CatalogTopic("film-books", "Film and books", "Society and culture", "plot, character, chapter, director"),
        CatalogTopic("religion-festivals", "Festivals and celebrations", "Society and culture", "holidays, traditions, gifts, ceremony"),
        CatalogTopic("countries", "Countries and nationalities", "Society and culture", "countries, nationalities, languages"),
        // Everyday life, in depth
        CatalogTopic("kitchen", "Kitchen and cooking", "Everyday life in depth", "utensils, appliances, cooking verbs"),
        CatalogTopic("shopping", "Shopping", "Everyday life in depth", "price, discount, receipt, size, till"),
        CatalogTopic("personality", "Personality", "Everyday life in depth", "character traits: generous, shy, stubborn"),
        CatalogTopic("relationships", "Relationships", "Everyday life in depth", "friendship, dating, wedding, divorce"),
        CatalogTopic("household", "Housework", "Everyday life in depth", "chores, cleaning products, laundry"),
        CatalogTopic("anatomy", "Body in depth", "Everyday life in depth", "organs, bones, muscles, skin"),
        CatalogTopic("airport-hotel", "Airport and hotel", "Everyday life in depth", "check-in, boarding, luggage, reception"),
        CatalogTopic("fabric-fashion", "Fashion and fabrics", "Everyday life in depth", "cotton, wool, pattern, fit, style"),
        CatalogTopic("sports-gear", "Sports and fitness", "Everyday life in depth", "gym, training, equipment, competition"),
    )

    // ------------------------------------------------ writing a set with Gemini

    private val SYSTEM = """
        You write vocabulary lists for a language learner who studies English, French, German, Italian, Spanish and Mandarin.
        Every row is one concept, translated accurately and naturally into all six languages. Return only JSON matching the schema.
    """.trimIndent()

    fun prompt(topic: String, hint: String?, count: Int, advanced: Boolean): String = buildString {
        appendLine("Topic: $topic" + (hint?.let { " ($it)" } ?: "") + ".")
        appendLine("Write $count words for this topic, " + if (advanced) "beyond the basics (B2–C1, still useful)." else "the most useful ones (A2–B1).")
        appendLine("Mostly nouns; add a few verbs or adjectives when they belong to the topic. No duplicates, no proper names.")
        appendLine("For each word:")
        appendLine("- en: the English word (verbs as \"to …\"; add a short gloss in parentheses only if ambiguous).")
        appendLine("- fr, de, it, es: nouns with their definite article (le/la/les, der/die/das, il/lo/la/i/gli/le, el/la/los/las). " +
            "When the article is elided (l'), add (m) or (f) after the word. People nouns with two forms: \"le vendeur / la vendeuse\". " +
            "Verbs in the infinitive, adjectives in the masculine singular.")
        appendLine("- zh: simplified characters; pinyin: with tone marks. If you give two alternatives in zh separated by \" / \", give the same in pinyin.")
        append("- title: the topic's name in English (2–4 words).")
    }

    fun schema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "STRING") }
            putJsonObject("words") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") { FIELDS.forEach { k -> putJsonObject(k) { put("type", "STRING") } } }
                    putJsonArray("required") { FIELDS.forEach { add(it) } }
                }
            }
        }
        putJsonArray("required") { add("title"); add("words") }
    }

    private val FIELDS = listOf("en", "fr", "de", "it", "es", "zh", "pinyin")
    private val LANGS = listOf(Lang.EN, Lang.FR, Lang.DE, Lang.IT, Lang.ES, Lang.ZH)

    /** Rows with every language filled in; null if fewer than 6 usable words came back. */
    fun parse(o: JsonObject, id: String, fallbackTitle: String, category: String): VocabSet? {
        val words = (o["words"] as? JsonArray).orEmpty().mapNotNull { e ->
            val w = e as? JsonObject ?: return@mapNotNull null
            fun f(k: String) = (w[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val forms = LANGS.zip(FIELDS).associate { (l, k) -> l to (f(k) ?: return@mapNotNull null) }
            val pinyin = f("pinyin") ?: return@mapNotNull null
            // Pinyin must line up with the characters' alternatives; otherwise keep only the first of each.
            val zhAlt = VocabCheck.alternatives(forms.getValue(Lang.ZH))
            val pyAlt = VocabCheck.alternatives(pinyin)
            if (zhAlt.size != pyAlt.size) VocabWord(forms + (Lang.ZH to zhAlt.first()), pyAlt.first()) else VocabWord(forms, pinyin)
        }.distinctBy { it.form(Lang.EN).lowercase() }
        if (words.size < 6) return null
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        return VocabSet(id, title, category, words)
    }

    suspend fun write(gemini: GeminiClient, apiKey: String, model: String, id: String, topic: String, hint: String?, category: String, count: Int, advanced: Boolean): VocabSet {
        val out = gemini.generateJson(apiKey, model, prompt(topic, hint, count, advanced), schema(), SYSTEM)
        return parse(out, id, topic, category) ?: throw GeminiClient.GeminiException("Gemini didn't return a usable word list. Try again.")
    }
}

/** Sets added from the catalog or your own topics, kept as JSON in app storage. */
class CustomVocabStore(private val file: File) {
    @Serializable
    private data class StoredWord(val forms: Map<String, String>, val pinyin: String)

    @Serializable
    private data class StoredSet(val id: String, val title: String, val category: String, val words: List<StoredWord>, val at: Long = System.currentTimeMillis())

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredSet.serializer())

    @Synchronized
    fun load(): List<VocabSet> = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()).map { s ->
        VocabSet(s.id, s.title, s.category, s.words.map { w -> VocabWord(w.forms.mapNotNull { (k, v) -> Lang.fromCode(k)?.let { it to v } }.toMap(), w.pinyin) }, custom = true)
    }

    @Synchronized
    fun save(set: VocabSet) = write(load().filter { it.id != set.id } + set)

    @Synchronized
    fun delete(id: String) = write(load().filter { it.id != id })

    private fun write(sets: List<VocabSet>) {
        file.parentFile?.mkdirs()
        val stored = sets.map { s -> StoredSet(s.id, s.title, s.category, s.words.map { w -> StoredWord(w.forms.mapKeys { it.key.code }, w.pinyin) }) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, stored))
        tmp.renameTo(file)
    }
}
