#!/usr/bin/env python3
"""
Build Diacritica's Rehearse sentence database from Tatoeba (CC BY 2.0 FR).

Uses the per-language exports from https://downloads.tatoeba.org/exports/per_language/ :
  <dir>/eng_sentences.tsv.bz2, fra_..., deu_..., ita_..., spa_..., cmn_sentences.tsv.bz2
  <dir>/eng-fra_links.tsv.bz2, eng-deu_..., eng-ita_..., eng-spa_..., eng-cmn_links.tsv.bz2
(files may also be uncompressed .tsv). English sentences are the anchors; each kept English
sentence becomes a "group" with its human translations in the other languages.

Difficulty: every English word is looked up in an English frequency list (word count per line,
most common first). The rarest word decides the level: A2 ≤ 2,000, B1 ≤ 5,000, B2 ≤ 12,000.

Example:
  python3 build_sentences.py --dir data/tatoeba --freq data/en_50k.txt --out ../app/src/main/assets/sentences/sentences.db
  python3 build_sentences.py --sample --out ../app/src/main/assets/sentences/sentences.db
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import io
import os
import random
import re
import sqlite3
import sys
import time

LANGS = {"fra": "fr", "deu": "de", "ita": "it", "spa": "es", "cmn": "zh"}
LEVELS = [(2000, "A2"), (5000, "B1"), (12000, "B2")]
WORD = re.compile(r"[A-Za-z']+")

SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE sgroup (id INTEGER PRIMARY KEY, level TEXT NOT NULL, rank INTEGER NOT NULL, words INTEGER NOT NULL,
                     langs INTEGER NOT NULL);
CREATE TABLE stext (group_id INTEGER NOT NULL, lang TEXT NOT NULL, text TEXT NOT NULL, ord INTEGER NOT NULL);
"""
INDEXES = """
CREATE INDEX idx_stext_group ON stext(group_id);
CREATE INDEX idx_sgroup_level ON sgroup(level);
"""

SAMPLE = [
    ("A2", {"en": "I left my keys at my sister's house.", "fr": "J'ai laissé mes clés chez ma sœur.",
            "de": "Ich habe meine Schlüssel bei meiner Schwester liegen lassen.", "it": "Ho lasciato le chiavi a casa di mia sorella.",
            "es": "Dejé mis llaves en casa de mi hermana.", "zh": "我把钥匙忘在姐姐家了。"}),
    ("A2", {"en": "We are going to the beach tomorrow.", "fr": "Nous allons à la plage demain.",
            "de": "Wir fahren morgen an den Strand.", "it": "Domani andiamo al mare.",
            "es": "Mañana vamos a la playa.", "zh": "我们明天去海边。"}),
    ("A2", {"en": "Could you speak more slowly, please?", "fr": "Pourriez-vous parler plus lentement, s'il vous plaît ?",
            "de": "Könnten Sie bitte langsamer sprechen?", "it": "Potrebbe parlare più lentamente, per favore?",
            "es": "¿Podría hablar más despacio, por favor?", "zh": "请您说慢一点，好吗？"}),
    ("B1", {"en": "The book I bought yesterday is very interesting.", "fr": "Le livre que j'ai acheté hier est très intéressant.",
            "de": "Das Buch, das ich gestern gekauft habe, ist sehr interessant.", "it": "Il libro che ho comprato ieri è molto interessante.",
            "es": "El libro que compré ayer es muy interesante.", "zh": "我昨天买的书很有意思。"}),
    ("B1", {"en": "If it rains, we will stay at home.", "fr": "S'il pleut, nous resterons à la maison.",
            "de": "Wenn es regnet, bleiben wir zu Hause.", "it": "Se piove, resteremo a casa.",
            "es": "Si llueve, nos quedaremos en casa.", "zh": "如果下雨，我们就待在家里。"}),
    ("B1", {"en": "She has been learning German for two years.", "fr": "Elle apprend l'allemand depuis deux ans.",
            "de": "Sie lernt seit zwei Jahren Deutsch.", "it": "Studia il tedesco da due anni.",
            "es": "Lleva dos años aprendiendo alemán.", "zh": "她学德语已经两年了。"}),
    ("A2", {"en": "I would like a coffee with milk.", "fr": "Je voudrais un café au lait.",
            "de": "Ich hätte gern einen Kaffee mit Milch.", "it": "Vorrei un caffellatte.",
            "es": "Quisiera un café con leche.", "zh": "我想要一杯加奶的咖啡。"}),
    ("A2", {"en": "The train leaves at eight o'clock.", "fr": "Le train part à huit heures.",
            "de": "Der Zug fährt um acht Uhr ab.", "it": "Il treno parte alle otto.",
            "es": "El tren sale a las ocho.", "zh": "火车八点出发。"}),
    ("A2", {"en": "My brother doesn't like to cook.", "fr": "Mon frère n'aime pas cuisiner.",
            "de": "Mein Bruder kocht nicht gern.", "it": "A mio fratello non piace cucinare.",
            "es": "A mi hermano no le gusta cocinar.", "zh": "我哥哥不喜欢做饭。"}),
    ("A2", {"en": "We need to buy bread and cheese.", "fr": "Nous devons acheter du pain et du fromage.",
            "de": "Wir müssen Brot und Käse kaufen.", "it": "Dobbiamo comprare pane e formaggio.",
            "es": "Tenemos que comprar pan y queso.", "zh": "我们需要买面包和奶酪。"}),
    ("B1", {"en": "I didn't know that you lived here.", "fr": "Je ne savais pas que tu habitais ici.",
            "de": "Ich wusste nicht, dass du hier wohnst.", "it": "Non sapevo che abitassi qui.",
            "es": "No sabía que vivías aquí.", "zh": "我不知道你住在这里。"}),
    ("B1", {"en": "It's too cold to go out today.", "fr": "Il fait trop froid pour sortir aujourd'hui.",
            "de": "Heute ist es zu kalt, um rauszugehen.", "it": "Oggi fa troppo freddo per uscire.",
            "es": "Hoy hace demasiado frío para salir.", "zh": "今天太冷了，不能出去。"}),
]


def open_text(path: str) -> io.TextIOBase:
    if path.endswith(".bz2"):
        return io.TextIOWrapper(bz2.open(path, "rb"), encoding="utf-8")
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8")
    return open(path, encoding="utf-8")


def find(d: str, stem: str) -> str | None:
    for ext in (".tsv.bz2", ".tsv.gz", ".tsv", ".csv"):
        p = os.path.join(d, stem + ext)
        if os.path.exists(p):
            return p
    return None


def load_freq(path: str) -> dict[str, int]:
    ranks: dict[str, int] = {}
    with open_text(path) as fh:
        for i, line in enumerate(fh):
            w = line.strip().split(" ")[0].lower()
            if w and w not in ranks:
                ranks[w] = i + 1
    return ranks


def level_for(text: str, ranks: dict[str, int]) -> tuple[str | None, int, int]:
    words = [w.lower().strip("'") for w in WORD.findall(text)]
    words = [w for w in words if w]
    if not words:
        return None, 0, 0
    worst = max(ranks.get(w, ranks.get(w.split("'")[0], 10**9)) for w in words)
    for limit, name in LEVELS:
        if worst <= limit:
            return name, worst, len(words)
    return None, worst, len(words)


def write_db(out: str, groups: list[tuple[int, str, int, int, dict[str, list[str]]]], source: str) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)
    con = sqlite3.connect(out)
    con.executescript(SCHEMA)
    for gid, level, rank, nwords, texts in groups:
        con.execute("INSERT INTO sgroup VALUES (?,?,?,?,?)", (gid, level, rank, nwords, len(texts)))
        for lang, variants in texts.items():
            con.executemany("INSERT INTO stext VALUES (?,?,?,?)", [(gid, lang, t, i) for i, t in enumerate(variants[:3])])
    con.executemany("INSERT INTO meta VALUES (?,?)", [
        ("schema_version", "1"), ("built_at", time.strftime("%Y-%m-%d")), ("source", source),
        ("groups", str(len(groups))),
    ])
    con.executescript(INDEXES)
    con.commit()
    con.execute("VACUUM")
    con.close()
    print(f"Done: {out} ({len(groups):,} sentence groups)", file=sys.stderr)


def build_sample(out: str) -> None:
    groups = []
    for i, (level, texts) in enumerate(SAMPLE, start=1):
        groups.append((i, level, 0, len(texts["en"].split()), {k: [v] for k, v in texts.items()}))
    write_db(out, groups, "Starter sample (hand-written)")


def build_tatoeba(args: argparse.Namespace) -> None:
    ranks = load_freq(args.freq)
    eng_path = find(args.dir, "eng_sentences")
    if not eng_path:
        sys.exit(f"eng_sentences.tsv(.bz2) not found in {args.dir}")
    t0 = time.time()
    anchors: dict[int, tuple[str, str, int, int]] = {}
    with open_text(eng_path) as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            sid, text = int(parts[0]), parts[2]
            level, rank, n = level_for(text, ranks)
            if level and args.min_words <= n <= args.max_words:
                anchors[sid] = (text, level, rank, n)
    print(f"English anchors at A2–B2: {len(anchors):,} ({time.time() - t0:.0f}s)", file=sys.stderr)

    links: dict[str, dict[int, list[int]]] = {}
    for code3, code in LANGS.items():
        lp = find(args.dir, f"eng-{code3}_links")
        if not lp:
            print(f"  (no eng-{code3}_links file, skipping {code})", file=sys.stderr)
            continue
        m: dict[int, list[int]] = {}
        with open_text(lp) as fh:
            for line in fh:
                a, _, b = line.strip().partition("\t")
                if a.isdigit() and b.isdigit() and int(a) in anchors:
                    m.setdefault(int(a), []).append(int(b))
        links[code] = m
        print(f"  links eng→{code}: {len(m):,}", file=sys.stderr)

    texts: dict[str, dict[int, str]] = {}
    for code3, code in LANGS.items():
        if code not in links:
            continue
        need = {t for ids in links[code].values() for t in ids}
        sp = find(args.dir, f"{code3}_sentences")
        if not sp:
            print(f"  (no {code3}_sentences file)", file=sys.stderr)
            continue
        got: dict[int, str] = {}
        with open_text(sp) as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3 and parts[0].isdigit() and int(parts[0]) in need:
                    got[int(parts[0])] = parts[2]
        texts[code] = got

    groups = []
    for sid, (en, level, rank, n) in anchors.items():
        tr = {"en": [en]}
        for code, m in links.items():
            variants = [texts.get(code, {}).get(t) for t in m.get(sid, [])]
            variants = [v for v in variants if v]
            if variants:
                tr[code] = variants
        if len(tr) - 1 >= args.min_langs:
            groups.append((sid, level, rank, n, tr))
    # Prefer sentences covering more languages; then a stable shuffle so levels and topics mix.
    random.Random(7).shuffle(groups)
    groups.sort(key=lambda g: -len(g[4]))
    # Balance levels: an equal share per level (best-covered first), then fill up with whatever is left.
    share = args.max_groups // len(LEVELS)
    picked, rest = [], []
    taken = {lv: 0 for _, lv in LEVELS}
    for g in groups:
        if taken[g[1]] < share:
            picked.append(g); taken[g[1]] += 1
        else:
            rest.append(g)
    groups = (picked + rest)[: args.max_groups]
    by_level = {lv: sum(1 for g in groups if g[1] == lv) for _, lv in LEVELS}
    print(f"Keeping {len(groups):,} groups: {by_level}", file=sys.stderr)
    write_db(args.out, groups, "Tatoeba (CC BY 2.0 FR), https://tatoeba.org")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dir", help="folder with the Tatoeba per-language files")
    p.add_argument("--freq", help="English frequency list (e.g. en_50k.txt)")
    p.add_argument("--out", required=True)
    p.add_argument("--sample", action="store_true", help="write the small hand-written starter set")
    p.add_argument("--min-words", type=int, default=4)
    p.add_argument("--max-words", type=int, default=14)
    p.add_argument("--min-langs", type=int, default=2, help="minimum number of translated languages per sentence")
    p.add_argument("--max-groups", type=int, default=8000)
    a = p.parse_args()
    if a.sample:
        build_sample(a.out)
    elif a.dir and a.freq:
        build_tatoeba(a)
    else:
        p.error("give --sample, or --dir and --freq")


if __name__ == "__main__":
    main()
