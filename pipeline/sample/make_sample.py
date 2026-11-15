#!/usr/bin/env python3
"""
Writes small hand-made inputs in the same shape as the kaikki.org / CC-CEDICT files and builds
the starter dictionary that ships in the APK (app/src/main/assets/dict/dictionary.db).

The starter covers ~15 words so the app is usable before you build the full dictionary.
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent.parent


def tr(lang, code, word, sense, *tags, roman=None):
    d = {"lang": lang, "lang_code": code, "word": word, "sense": sense, "tags": list(tags)}
    if roman:
        d["roman"] = roman
    return d


def fr(w, s, *t): return tr("French", "fr", w, s, *t)
def de(w, s, *t): return tr("German", "de", w, s, *t)
def it(w, s, *t): return tr("Italian", "it", w, s, *t)
def es(w, s, *t): return tr("Spanish", "es", w, s, *t)
def zh(w, s, roman, *t): return tr("Mandarin", "cmn", w, s, *t, roman=roman)


def en_entry(word, pos, ipa, plural, senses, translations):
    e = {"word": word, "lang": "English", "lang_code": "en", "pos": pos, "sounds": [{"ipa": ipa}],
         "senses": [{"glosses": [g], "examples": ([{"text": ex}] if ex else [])} for g, ex in senses],
         "translations": translations}
    if plural:
        e["forms"] = [{"form": plural, "tags": ["plural"]}]
    return e


S_LOCK = "device used to open a lock"
S_CRUCIAL = "crucial; of the greatest importance"
S_MUSIC = "musical tonality"
S_KEYBOARD = "button on a keyboard"
S_HOUSE = "abode"
S_BOOK = "collection of sheets of paper bound together"
S_WATER = "clear liquid H₂O"
S_DOG = "animal"
S_EAT = "to consume food"
S_BEAUTIFUL = "attractive, pleasing to the eye"
S_SEA = "large body of salt water"
S_BANK_FIN = "institution where one can place and borrow money"
S_BANK_RIVER = "edge of a river"

ENGLISH = [
    en_entry("key", "noun", "/kiː/", "keys",
             [("An object designed to open and close a lock.", "I lost the key to the front door."),
              ("A tonality of music.", "The song is in the key of C."),
              ("A button on a keyboard.", "Press any key to continue.")],
             [fr("clé", S_LOCK, "feminine"), de("Schlüssel", S_LOCK, "masculine"),
              it("chiave", S_LOCK, "feminine"), es("llave", S_LOCK, "feminine"),
              zh("鑰匙", S_LOCK, "yàoshi", "Traditional-Chinese"), zh("钥匙", S_LOCK, "yàoshi", "Simplified-Chinese"),
              fr("tonalité", S_MUSIC, "feminine"), de("Tonart", S_MUSIC, "feminine"),
              it("tonalità", S_MUSIC, "feminine"), es("tonalidad", S_MUSIC, "feminine"), zh("调", S_MUSIC, "diào"),
              fr("touche", S_KEYBOARD, "feminine"), de("Taste", S_KEYBOARD, "feminine"),
              it("tasto", S_KEYBOARD, "masculine"), es("tecla", S_KEYBOARD, "feminine"), zh("键", S_KEYBOARD, "jiàn")]),
    en_entry("key", "adj", "/kiː/", None,
             [("Indispensable, supremely important.", "She played a key role.")],
             [fr("clé", S_CRUCIAL), it("chiave", S_CRUCIAL), es("clave", S_CRUCIAL),
              de("entscheidend", S_CRUCIAL), zh("关键", S_CRUCIAL, "guānjiàn")]),
    en_entry("house", "noun", "/haʊs/", "houses", [("A structure serving as an abode of human beings.", None)],
             [fr("maison", S_HOUSE, "feminine"), de("Haus", S_HOUSE, "neuter"), it("casa", S_HOUSE, "feminine"),
              es("casa", S_HOUSE, "feminine"), zh("房子", S_HOUSE, "fángzi")]),
    en_entry("book", "noun", "/bʊk/", "books", [("A collection of sheets of paper bound together.", None)],
             [fr("livre", S_BOOK, "masculine"), de("Buch", S_BOOK, "neuter"), it("libro", S_BOOK, "masculine"),
              es("libro", S_BOOK, "masculine"), zh("书", S_BOOK, "shū")]),
    en_entry("water", "noun", "/ˈwɔːtə/", None, [("A substance (of molecular formula H₂O).", None)],
             [fr("eau", S_WATER, "feminine"), de("Wasser", S_WATER, "neuter"), it("acqua", S_WATER, "feminine"),
              es("agua", S_WATER, "feminine"), zh("水", S_WATER, "shuǐ")]),
    en_entry("dog", "noun", "/dɒɡ/", "dogs", [("A mammal, Canis familiaris.", None)],
             [fr("chien", S_DOG, "masculine"), de("Hund", S_DOG, "masculine"), it("cane", S_DOG, "masculine"),
              es("perro", S_DOG, "masculine"), zh("狗", S_DOG, "gǒu")]),
    en_entry("eat", "verb", "/iːt/", None, [("To ingest; to be ingested.", None)],
             [fr("manger", S_EAT), de("essen", S_EAT), it("mangiare", S_EAT), es("comer", S_EAT),
              zh("吃", S_EAT, "chī")]),
    en_entry("beautiful", "adj", "/ˈbjuːtɪfəl/", None, [("Attractive; possessing beauty.", None)],
             [fr("beau", S_BEAUTIFUL), de("schön", S_BEAUTIFUL), it("bello", S_BEAUTIFUL),
              es("hermoso", S_BEAUTIFUL), es("bello", S_BEAUTIFUL), zh("美丽", S_BEAUTIFUL, "měilì")]),
    en_entry("sea", "noun", "/siː/", "seas", [("A large body of salt water.", None)],
             [fr("mer", S_SEA, "feminine"), de("Meer", S_SEA, "neuter"), it("mare", S_SEA, "masculine"),
              es("mar", S_SEA, "masculine", "feminine"), zh("海", S_SEA, "hǎi")]),
    en_entry("bank", "noun", "/bæŋk/", "banks",
             [("An institution where one can place and borrow money.", None), ("The edge of a river.", None)],
             [fr("banque", S_BANK_FIN, "feminine"), de("Bank", S_BANK_FIN, "feminine"),
              it("banca", S_BANK_FIN, "feminine"), es("banco", S_BANK_FIN, "masculine"),
              zh("银行", S_BANK_FIN, "yínháng"),
              fr("rive", S_BANK_RIVER, "feminine"), de("Ufer", S_BANK_RIVER, "neuter"),
              it("riva", S_BANK_RIVER, "feminine"), es("orilla", S_BANK_RIVER, "feminine"),
              zh("岸", S_BANK_RIVER, "àn")]),
]


def entry(lang, code, word, pos, head, ipa, glosses, forms=(), example=None, example_en=None, form_of=None):
    senses = [{"glosses": [g]} for g in glosses]
    if example:
        senses[0]["examples"] = [{"text": example, "english": example_en}]
    if form_of:
        senses[0]["form_of"] = [{"word": form_of}]
        senses[0]["tags"] = ["form-of"]
    return {"word": word, "lang": lang, "lang_code": code, "pos": pos,
            "head_templates": [{"expansion": head}] if head else [],
            "sounds": [{"ipa": ipa}] if ipa else [], "senses": senses,
            "forms": [{"form": f, "tags": t} for f, t in forms]}


FRENCH = [
    entry("French", "fr", "clé", "noun", "clé f (plural clés)", "/kle/", ["key (device for opening locks)", "key (crucial)"],
          [("clés", ["plural"])], "J'ai oublié mes clés.", "I forgot my keys."),
    entry("French", "fr", "clés", "noun", None, "/kle/", ["plural of clé"], form_of="clé"),
    entry("French", "fr", "maison", "noun", "maison f (plural maisons)", "/mɛ.zɔ̃/", ["house"],
          [("maisons", ["plural"])], "Ma maison est petite.", "My house is small."),
    entry("French", "fr", "livre", "noun", "livre m (plural livres)", "/livʁ/", ["book"], [("livres", ["plural"])]),
    entry("French", "fr", "eau", "noun", "eau f (plural eaux)", "/o/", ["water"], [("eaux", ["plural"])]),
    entry("French", "fr", "chien", "noun", "chien m (plural chiens, feminine chienne)", "/ʃjɛ̃/", ["dog"],
          [("chiens", ["plural"]), ("chienne", ["feminine"])]),
    entry("French", "fr", "manger", "verb", "manger", "/mɑ̃.ʒe/", ["to eat"],
          [("mange", ["first-person", "singular", "present", "indicative"]),
           ("manges", ["second-person", "singular", "present", "indicative"]),
           ("mangeons", ["first-person", "plural", "present", "indicative"]),
           ("mangé", ["past", "participle"])], "Nous mangeons à midi.", "We eat at noon."),
    entry("French", "fr", "mer", "noun", "mer f (plural mers)", "/mɛʁ/", ["sea"], [("mers", ["plural"])]),
    entry("French", "fr", "banque", "noun", "banque f (plural banques)", "/bɑ̃k/", ["bank (financial institution)"],
          [("banques", ["plural"])]),
    entry("French", "fr", "touche", "noun", "touche f (plural touches)", "/tuʃ/", ["key (on a keyboard)"],
          [("touches", ["plural"])]),
]
GERMAN = [
    entry("German", "de", "Schlüssel", "noun", "Schlüssel m (strong, genitive Schlüssels, plural Schlüssel)",
          "/ˈʃlʏsl̩/", ["key (device for opening locks)"],
          [("Schlüssels", ["genitive", "singular"]), ("Schlüssel", ["nominative", "plural"])],
          "Wo ist mein Schlüssel?", "Where is my key?"),
    entry("German", "de", "Haus", "noun", "Haus n (strong, genitive Hauses, plural Häuser)", "/haʊ̯s/", ["house"],
          [("Hauses", ["genitive", "singular"]), ("Häuser", ["nominative", "plural"])]),
    entry("German", "de", "Buch", "noun", "Buch n (strong, genitive Buches, plural Bücher)", "/buːx/", ["book"],
          [("Buches", ["genitive", "singular"]), ("Bücher", ["nominative", "plural"])]),
    entry("German", "de", "Hund", "noun", "Hund m (strong, genitive Hundes, plural Hunde)", "/hʊnt/", ["dog"],
          [("Hundes", ["genitive", "singular"]), ("Hunde", ["nominative", "plural"])]),
    entry("German", "de", "essen", "verb", "essen (class 5 strong, third-person singular present isst)", "/ˈɛsn̩/",
          ["to eat"], [("esse", ["first-person", "singular", "present"]), ("isst", ["third-person", "singular", "present"]),
                       ("aß", ["first-person", "singular", "preterite"]), ("gegessen", ["past", "participle"])]),
    entry("German", "de", "Meer", "noun", "Meer n (strong, genitive Meeres, plural Meere)", "/meːɐ̯/", ["sea"],
          [("Meere", ["nominative", "plural"])]),
    entry("German", "de", "Bank", "noun", "Bank f (genitive Bank, plural Banken)", "/baŋk/", ["bank (financial institution)", "bench"],
          [("Banken", ["nominative", "plural"])]),
    entry("German", "de", "Ufer", "noun", "Ufer n (strong, genitive Ufers, plural Ufer)", "/ˈuːfɐ/", ["shore, bank"],
          [("Ufer", ["nominative", "plural"])]),
    entry("German", "de", "Taste", "noun", "Taste f (genitive Taste, plural Tasten)", "/ˈtastə/", ["key (on a keyboard)"],
          [("Tasten", ["nominative", "plural"])]),
]
ITALIAN = [
    entry("Italian", "it", "chiave", "noun", "chiave f (plural chiavi)", "/ˈkja.ve/", ["key"], [("chiavi", ["plural"])],
          "Ho perso le chiavi.", "I lost the keys."),
    entry("Italian", "it", "chiavi", "noun", None, None, ["plural of chiave"], form_of="chiave"),
    entry("Italian", "it", "casa", "noun", "casa f (plural case)", "/ˈka.sa/", ["house, home"], [("case", ["plural"])]),
    entry("Italian", "it", "libro", "noun", "libro m (plural libri)", "/ˈli.bro/", ["book"], [("libri", ["plural"])]),
    entry("Italian", "it", "acqua", "noun", "acqua f (plural acque)", "/ˈak.kwa/", ["water"], [("acque", ["plural"])]),
    entry("Italian", "it", "cane", "noun", "cane m (plural cani)", "/ˈka.ne/", ["dog"], [("cani", ["plural"])]),
    entry("Italian", "it", "mare", "noun", "mare m (plural mari)", "/ˈma.re/", ["sea"], [("mari", ["plural"])]),
    entry("Italian", "it", "tasto", "noun", "tasto m (plural tasti)", "/ˈtas.to/", ["key (on a keyboard)"],
          [("tasti", ["plural"])]),
]
SPANISH = [
    entry("Spanish", "es", "llave", "noun", "llave f (plural llaves)", "/ˈʝa.βe/", ["key"], [("llaves", ["plural"])],
          "¿Dónde están las llaves?", "Where are the keys?"),
    entry("Spanish", "es", "casa", "noun", "casa f (plural casas)", "/ˈka.sa/", ["house"], [("casas", ["plural"])]),
    entry("Spanish", "es", "libro", "noun", "libro m (plural libros)", "/ˈli.βɾo/", ["book"], [("libros", ["plural"])]),
    entry("Spanish", "es", "agua", "noun", "agua f (plural aguas)", "/ˈa.ɣwa/", ["water"], [("aguas", ["plural"])]),
    entry("Spanish", "es", "perro", "noun", "perro m (plural perros, feminine perra)", "/ˈpe.ro/", ["dog"],
          [("perros", ["plural"])]),
    entry("Spanish", "es", "comer", "verb", "comer", "/koˈmeɾ/", ["to eat"],
          [("como", ["first-person", "singular", "present", "indicative"]),
           ("comí", ["first-person", "singular", "preterite", "indicative"])]),
    entry("Spanish", "es", "mar", "noun", "mar m or f (plural mares)", "/maɾ/", ["sea"], [("mares", ["plural"])]),
    entry("Spanish", "es", "tecla", "noun", "tecla f (plural teclas)", "/ˈte.kla/", ["key (on a keyboard)"],
          [("teclas", ["plural"])]),
]

CEDICT = """# Sample in CC-CEDICT format
鑰匙 钥匙 [yao4 shi5] /key/CL:把[ba3]/
房子 房子 [fang2 zi5] /house/building/CL:所[suo3],套[tao4]/
書 书 [shu1] /book/letter/CL:本[ben3],冊|册[ce4]/
水 水 [shui3] /water/river/
狗 狗 [gou3] /dog/CL:隻|只[zhi1],條|条[tiao2]/
吃 吃 [chi1] /to eat/
美麗 美丽 [mei3 li4] /beautiful/
海 海 [hai3] /ocean/sea/
銀行 银行 [yin2 hang2] /bank/CL:家[jia1],個|个[ge4]/
岸 岸 [an4] /bank/shore/coast/
關鍵 关键 [guan1 jian4] /crucial point/crux/key/
調 调 [diao4] /tone/tune/key (in music)/
鍵 键 [jian4] /key (on a piano or keyboard)/
你好 你好 [ni3 hao3] /hello/hi/
女兒 女儿 [nu:3 er2] /daughter/CL:個|个[ge4]/
西安 西安 [Xi1 an1] /Xi'an, capital of Shaanxi/
"""

# HSK 2.0 levels; only words whose level is well established are listed.
HSK = "书\t1\n水\t1\n狗\t1\n吃\t1\n女儿\t1\n银行\t3\n"


def write_jsonl(path, items):
    path.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in items) + "\n", encoding="utf-8")


def main():
    data = HERE / "data"
    data.mkdir(exist_ok=True)
    write_jsonl(data / "en.jsonl", ENGLISH)
    write_jsonl(data / "fr.jsonl", FRENCH)
    write_jsonl(data / "de.jsonl", GERMAN)
    write_jsonl(data / "it.jsonl", ITALIAN)
    write_jsonl(data / "es.jsonl", SPANISH)
    (data / "cedict.u8").write_text(CEDICT, encoding="utf-8")
    (data / "hsk.tsv").write_text(HSK, encoding="utf-8")
    out = ROOT / "app/src/main/assets/dict/dictionary.db"
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(HERE.parent / "build_dictionary.py"),
                    "--en", str(data / "en.jsonl"), "--fr", str(data / "fr.jsonl"), "--de", str(data / "de.jsonl"),
                    "--it", str(data / "it.jsonl"), "--es", str(data / "es.jsonl"),
                    "--cedict", str(data / "cedict.u8"), "--hsk", str(data / "hsk.tsv"),
                    "--label", "Starter sample (15 words)", "--out", str(out)], check=True)


if __name__ == "__main__":
    main()
