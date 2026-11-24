package app.diacritica.grammar

import app.diacritica.data.Lang

/**
 * A grammar topic for "Lesson of the day": advanced enough to be worth a full lesson, the kind that's easy to
 * avoid for years. [tags] use Rehearse's error types, so your recent mistakes push related topics up.
 */
data class DailyTopic(
    val id: String,
    val lang: Lang,
    val title: String,
    /** The structure, in the language itself. */
    val pattern: String,
    /** Why it's worth it, one line. */
    val blurb: String,
    val tags: List<String>,
) {
    /** What Gemini is asked to write the lesson about. */
    val request: String get() = "$title ($pattern). $blurb"
}

/** The built-in topics (French, German, Italian, Mandarin). Chosen offline; the lesson is written by Gemini. */
object DailyTopics {
    private fun t(lang: Lang, n: Int, title: String, pattern: String, blurb: String, vararg tags: String) =
        DailyTopic("${lang.code}-%02d".format(n), lang, title, pattern, blurb, tags.toList())

    private val fr = Lang.FR
    private val de = Lang.DE
    private val it = Lang.IT
    private val zh = Lang.ZH

    val all: List<DailyTopic> = listOf(
        // ------------------------------------------------------------------ French
        t(fr, 1, "The subjunctive after conjunctions", "bien que, pour que, avant que, à moins que + subjonctif", "Which conjunctions force the subjunctive, and the ones that look similar but don't (après que, parce que).", "conjugation"),
        t(fr, 2, "The past subjunctive", "que j'aie fini, qu'elle soit partie", "When the action in the subjunctive clause happened before the main one.", "conjugation"),
        t(fr, 3, "Sequence of tenses in reported speech", "il a dit qu'il viendrait / qu'il était venu", "How every tense shifts when you report what someone said in the past.", "conjugation"),
        t(fr, 4, "Passé composé, imparfait and plus-que-parfait in a story", "j'avais déjà mangé quand il est arrivé", "Choosing the right past tense for background, events and what happened before.", "conjugation"),
        t(fr, 5, "The passé simple", "il fut, elle prit, ils vinrent", "The literary past of novels and history books: recognizing and forming it.", "conjugation"),
        t(fr, 6, "Unreal past conditions", "si j'avais su, je serais venu", "Si + plus-que-parfait with the past conditional, and the mixed forms.", "conjugation"),
        t(fr, 7, "Past participle agreement with avoir", "les lettres que j'ai écrites", "Agreeing with a direct object placed before the verb, and when not to.", "agreement"),
        t(fr, 8, "Agreement of reflexive verbs", "elle s'est lavée / elle s'est lavé les mains", "Why the participle agrees in one sentence and not the other.", "agreement"),
        t(fr, 9, "Compound relative pronouns", "lequel, auquel, duquel, avec lequel", "Relative pronouns after prepositions, and how they contract.", "word choice"),
        t(fr, 10, "Dont, que and où", "le livre dont je parle, le jour où", "Choosing the relative pronoun by what the verb needs (parler de, penser à…).", "word choice"),
        t(fr, 11, "Y, en and the order of double pronouns", "je le lui donne, il y en a, donne-le-moi", "The fixed order of object pronouns, including in the imperative.", "word order"),
        t(fr, 12, "The expletive ne", "avant qu'il ne parte, de peur qu'il ne pleuve", "A ne that doesn't negate: where it appears and what it signals.", "register"),
        t(fr, 13, "Emphasis with c'est … qui / que", "c'est lui qui l'a fait, c'est demain que je pars", "Highlighting one part of a sentence, with agreement after qui.", "word order"),
        t(fr, 14, "Subject–verb inversion", "peut-être viendra-t-il, aussi a-t-il refusé", "Inversion in questions and after certain adverbs in formal French.", "word order", "register"),
        t(fr, 15, "Gérondif, present participle and verbal adjective", "en marchant / marchant / fatigant vs fatiguant", "Three -ant forms with different jobs and spellings.", "agreement"),
        t(fr, 16, "Causative faire and se faire", "je fais réparer ma voiture, il s'est fait voler", "Getting something done, and the passive-like se faire.", "word order"),
        t(fr, 17, "The future perfect", "quand tu auras fini, on partira", "An action finished before another future one, and guesses about the past.", "conjugation"),
        t(fr, 18, "Articles after negation and quantity", "pas de pain, beaucoup de gens, bien des gens", "When du/de la/des become de, and the exceptions.", "articles"),
        t(fr, 19, "Adjectives that change meaning with position", "un grand homme / un homme grand, mon ancien appartement", "Before or after the noun can change the meaning entirely.", "word order"),
        t(fr, 20, "Tout as adverb and adjective", "toute contente, tout étonnées, tous les jours", "When tout agrees and when it doesn't.", "agreement"),
        t(fr, 21, "Quelque, quel que and quoi que", "quoi qu'il dise, quelles que soient les raisons", "Concessive forms that are easy to confuse in writing.", "word choice"),
        t(fr, 22, "Passive voice versus on and se faire", "le pont a été construit / on a construit le pont", "French prefers active forms: when each sounds natural.", "register"),
        t(fr, 23, "Complex negation", "ne … guère, ne … que, ni … ni, ne … plus personne", "Restrictive and combined negations.", "word choice"),
        t(fr, 24, "Depuis, il y a, pendant, pour, dans", "j'habite ici depuis deux ans", "Time expressions and the tenses they go with.", "word choice"),
        t(fr, 25, "The past infinitive", "après avoir mangé, sans être vu", "Infinitive clauses for actions that happened before.", "conjugation"),

        // ------------------------------------------------------------------ German
        t(de, 1, "Konjunktiv II for politeness and hypotheses", "ich hätte gern, könnten Sie, wenn ich Zeit hätte", "würde + infinitive vs. the synthetic forms, and which verbs keep their own.", "conjugation"),
        t(de, 2, "Konjunktiv II in the past", "ich hätte es gemacht, wir wären gegangen", "Unreal past conditions and regrets, including with modal verbs.", "conjugation"),
        t(de, 3, "Konjunktiv I in reported speech", "er sagt, er sei krank und habe keine Zeit", "The newspaper form of indirect speech, and when Konjunktiv II replaces it.", "conjugation", "register"),
        t(de, 4, "Process passive vs. state passive", "die Tür wird geöffnet / die Tür ist geöffnet", "werden + participle for actions, sein + participle for results.", "conjugation"),
        t(de, 5, "Passive with modal verbs", "das muss gemacht werden, das hätte gemacht werden müssen", "Stacking modal and passive, and where everything goes in the sentence.", "word order"),
        t(de, 6, "Alternatives to the passive", "das lässt sich machen, das ist zu tun, machbar", "sich lassen, sein + zu and -bar adjectives.", "register"),
        t(de, 7, "Two-way prepositions", "in die Stadt (wohin?) / in der Stadt (wo?)", "Accusative for movement, dative for location, and the verb pairs legen/liegen.", "case"),
        t(de, 8, "Genitive prepositions", "wegen des Wetters, trotz des Regens, während der Ferien", "Formal genitive and the everyday dative alternative.", "case"),
        t(de, 9, "Adjective endings", "der gute Wein, ein guter Wein, guter Wein", "Weak, mixed and strong declension in one system.", "agreement", "case"),
        t(de, 10, "Relative clauses with prepositions and wessen", "der Freund, mit dem ich spreche; die Frau, deren Auto", "Relative pronouns in every case, including the genitive.", "case", "word order"),
        t(de, 11, "The double infinitive", "dass er hat kommen müssen, ich habe ihn singen hören", "Modal verbs in the perfect, and the word order in subordinate clauses.", "word order"),
        t(de, 12, "Separable and inseparable prefixes", "übersetzen / übersetzen, umfahren / umfahren", "Verbs whose meaning changes with stress and separability.", "conjugation"),
        t(de, 13, "Verbs with prepositions and da-/wo- compounds", "ich freue mich darauf, worüber denkst du nach?", "Replacing preposition + thing with darauf, worüber….", "case"),
        t(de, 14, "Extended participle phrases", "die vom Arzt empfohlene Diät", "Long attributes before the noun, typical of written German.", "word order", "register"),
        t(de, 15, "Participles as adjectives", "das schlafende Kind, die geschlossene Tür", "Partizip I and II before nouns, with endings.", "agreement"),
        t(de, 16, "Weak masculine nouns (n-declension)", "den Kollegen, dem Studenten, des Herrn", "Masculine nouns that add -(e)n outside the nominative.", "case"),
        t(de, 17, "Modal particles", "doch, ja, mal, eben, halt, wohl", "Small words that change the tone of a sentence.", "register"),
        t(de, 18, "Word order in the middle field", "Ich habe ihm gestern wegen des Regens im Auto das Buch gegeben.", "Pronouns before nouns, time–cause–manner–place, and where nicht goes.", "word order"),
        t(de, 19, "Infinitive clauses", "um … zu, ohne … zu, statt … zu, es ist schwer, … zu", "zu + infinitive and the conjunctions that introduce it.", "word order"),
        t(de, 20, "Connectors and their word order", "obwohl / trotzdem, weil / denn / deshalb", "Same meaning, three different word orders.", "word order"),
        t(de, 21, "Als, wenn and wann", "als ich klein war, wenn ich Zeit habe, wann kommst du?", "Three ways to say 'when'.", "word choice"),
        t(de, 22, "Futur II for assumptions", "er wird es vergessen haben", "Guessing about the past with the future perfect.", "conjugation"),
        t(de, 23, "Nominal style", "nach der Ankunft des Zuges / nachdem der Zug angekommen ist", "Turning clauses into nouns, as in formal writing.", "register"),
        t(de, 24, "Reflexive verbs with the dative", "ich wasche mir die Hände, ich merke mir das", "When the reflexive pronoun is dative instead of accusative.", "case"),
        t(de, 25, "Two-part conjunctions", "sowohl … als auch, weder … noch, je … desto", "Paired conjunctions and their word order.", "word order"),

        // ------------------------------------------------------------------ Italian
        t(it, 1, "The present subjunctive", "penso che sia, è importante che tu venga", "After verbs of opinion, wish and emotion, and impersonal expressions.", "conjugation"),
        t(it, 2, "Imperfect and pluperfect subjunctive", "volevo che venissi, pensavo che fosse partito", "The past forms of the subjunctive.", "conjugation"),
        t(it, 3, "Conditional sentences", "se avessi tempo, verrei; se avessi saputo, sarei venuto", "Real, possible and unreal conditions.", "conjugation"),
        t(it, 4, "Sequence of tenses with the subjunctive", "credo che sia / credevo che fosse / credevo che fosse stato", "Matching the subjunctive to the main verb's tense.", "conjugation"),
        t(it, 5, "The passato remoto", "fu, ebbe, nacque, disse", "The narrative past of books and of the South.", "conjugation"),
        t(it, 6, "Trapassato prossimo and remoto", "avevo già mangiato / dopo che ebbe finito", "Actions before other past actions.", "conjugation"),
        t(it, 7, "The past conditional as future in the past", "disse che sarebbe venuto", "Italian uses the past conditional where English says 'would come'.", "conjugation"),
        t(it, 8, "Combined pronouns", "glielo, me ne, ce lo, te la", "Pairs of pronouns, their order and spelling.", "word order"),
        t(it, 9, "Ci and ne", "ci penso io, ne ho due, non ne posso più", "All the jobs these two small words do.", "word choice"),
        t(it, 10, "Impersonal si and passive si", "si mangia bene, si vendono case", "Why the verb is plural in one and singular in the other.", "agreement"),
        t(it, 11, "Past participle agreement with avere", "li ho visti, l'ho comprata", "Agreement with direct object pronouns before the verb.", "agreement"),
        t(it, 12, "Essere or avere as auxiliary", "ho corso / sono corso a casa, ho salito le scale / sono salito", "Verbs that take both, with different meanings.", "conjugation"),
        t(it, 13, "Relative pronouns cui, il quale, chi", "la persona a cui penso, il quale, chi cerca trova", "Relative pronouns after prepositions and in formal style.", "word choice"),
        t(it, 14, "The gerund and stare + gerund", "camminando, avendo finito, sto leggendo", "Present and past gerund, and the progressive.", "conjugation"),
        t(it, 15, "The infinitive as a noun and the past infinitive", "il mangiare, dopo aver mangiato", "Infinitives doing a noun's job.", "register"),
        t(it, 16, "Fare and lasciare + infinitive", "ho fatto riparare la macchina, lasciami parlare", "Causative constructions and where the pronouns go.", "word order"),
        t(it, 17, "Reported speech", "ha detto che sarebbe venuto il giorno dopo", "Changes in tense, pronouns and time words.", "conjugation"),
        t(it, 18, "The imperative with pronouns", "dimmelo, fallo, non dirmelo / non me lo dire", "Attaching pronouns, and the doubled consonants.", "word order"),
        t(it, 19, "Articulated prepositions and a/in with places", "nella, dalle, in macchina, a Roma, in Italia", "Contractions and which preposition goes with which place.", "articles"),
        t(it, 20, "Articles with possessives and family", "mia madre / la mia mamma / le mie sorelle", "When the possessive takes an article.", "articles"),
        t(it, 21, "Irregular comparatives and superlatives", "migliore, ottimo, peggiore, pessimo", "Forms beside più buono and the difference in use.", "word choice"),
        t(it, 22, "Pronominal verbs", "andarsene, farcela, cavarsela, prendersela", "Idiomatic verbs built with pronouns.", "word choice"),
        t(it, 23, "The passive with venire and andare", "viene pagato, va fatto subito", "Passive alternatives, including the sense of obligation.", "conjugation"),
        t(it, 24, "Concessive clauses", "benché, sebbene, nonostante + congiuntivo", "'Although' and the subjunctive it needs.", "conjugation"),
        t(it, 25, "The future of probability", "sarà stanco, avrà dimenticato", "Guessing with the future and future perfect.", "conjugation"),

        // ------------------------------------------------------------------ Mandarin
        t(zh, 1, "把 sentences", "把 + object + verb + result", "Doing something to a specific thing: when 把 is needed and when it's wrong.", "word order"),
        t(zh, 2, "The 被 passive and its alternatives", "被 / 叫 / 让 / 给", "Passive sentences, often for things that went badly.", "word order"),
        t(zh, 3, "Result complements", "听懂, 做完, 找到, 看见", "A second verb or adjective that says how the action ended.", "aspect"),
        t(zh, 4, "Potential complements", "听得懂 / 听不懂, 来得及, 吃不了", "Saying whether something can be done, with 得 or 不.", "aspect"),
        t(zh, 5, "Directional complements", "拿出来, 跑进去, 想起来", "来/去 and compound directions, including figurative uses.", "word order"),
        t(zh, 6, "Degree complements with 得", "说得很快, 高兴得跳起来", "Describing how an action is done, and the repeated verb with objects.", "word order"),
        t(zh, 7, "The two kinds of 了", "我吃了饭 / 下雨了", "Completed action versus change of state.", "aspect"),
        t(zh, 8, "过 versus 了", "我去过中国 / 我去了中国", "Experience versus completion.", "aspect"),
        t(zh, 9, "着 for continuing states", "门开着, 坐着吃饭", "States that last and actions done while in a state.", "aspect"),
        t(zh, 10, "The 是…的 construction", "我是昨天来的", "Asking and telling the when, where and how of a past event.", "word order"),
        t(zh, 11, "Topic–comment sentences", "这本书我看过了", "Putting what you talk about first.", "word order"),
        t(zh, 12, "Measure words beyond 个", "张, 条, 本, 件, 把, 只, 辆", "Which shapes and kinds of things take which measure word.", "word choice"),
        t(zh, 13, "就 and 才", "他八点就来了 / 他十点才来", "Earlier or later than expected.", "word choice"),
        t(zh, 14, "又, 再 and 还", "他又来了 / 明天再来 / 我还要", "Three ways to say 'again' or 'more'.", "word choice"),
        t(zh, 15, "Comparisons", "比, 没有…那么, 跟…一样, 比…多了", "Saying more, less and the same, with the right word order.", "word order"),
        t(zh, 16, "连…都 / 也", "他连一个字都不认识", "'Even' for emphasis.", "word order"),
        t(zh, 17, "越来越 and 越…越", "天气越来越冷, 越学越喜欢", "Things changing more and more.", "word order"),
        t(zh, 18, "Conditional pairs", "如果…就, 只要…就, 只有…才, 要不是", "Different kinds of 'if' and 'only if'.", "word choice"),
        t(zh, 19, "Concessive pairs", "虽然…但是, 即使…也, 不管…都", "'Although', 'even if' and 'no matter'.", "word choice"),
        t(zh, 20, "Question words as 'any' and 'every'", "谁都知道, 什么都不吃, 哪儿也不去", "Question words that don't ask anything.", "word order"),
        t(zh, 21, "Reduplication", "看看, 试一试, 高高兴兴", "Doubling verbs and adjectives, and what it adds.", "register"),
        t(zh, 22, "Duration and time words", "我学了两年中文 / 我两年没学中文", "Where 'how long' goes, and why it moves with negation.", "word order"),
        t(zh, 23, "Separable verbs", "见面, 帮忙, 睡觉 → 见了一面, 帮我的忙", "Verbs that split around other words.", "word order"),
        t(zh, 24, "会, 能 and 可以", "我会游泳 / 我能游一公里 / 可以游泳吗", "Ability, possibility and permission.", "word choice"),
        t(zh, 25, "Tone sandhi", "你好, 一个 / 一天, 不是 / 不好", "How 3rd tones, 一 and 不 change in speech.", "tones"),
    )
}
