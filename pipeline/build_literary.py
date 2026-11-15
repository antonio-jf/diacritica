#!/usr/bin/env python3
"""
Build Diacritica's literary sentences (Rehearse › Literary) from Project Gutenberg.

Downloads public-domain novels and stories written in English, French, German, Italian and
Spanish by a curated list of authors (originals, not translations), cuts them into sentences,
keeps clean ones of a practice-friendly length and grades them with the same frequency lists as
the dictionary (`<lang>_50k.txt`, one "word count" per line, most common first). The rarest word
decides the level: B1 ≤ 5,000, B2 ≤ 12,000, C1 ≤ 30,000; rarer sentences are dropped.

The app shows the original sentence; Gemini writes the reference translations (once, cached).

Run on your computer (it needs internet; books are cached in data/gutenberg/, so reruns are fast):
  cd pipeline
  python3 build_literary.py --freq-dir data --out ../app/src/main/assets/literary/literary.db

Options:
  --books N        books per language (default 8, at most 2 per author)
  --per-lang N     sentences kept per language (default 1500, spread over levels and books)
  --ids FILE       use these Gutenberg ebook numbers instead of the catalog ("lang id" per line)
  --langs en,fr    only some languages

Be gentle with gutenberg.org: downloads are sequential with a pause between them, and each book is
fetched only once. Texts are public domain in the USA; check your local law if you're elsewhere.
"""
from __future__ import annotations

import argparse
import csv
import io
import os
import random
import re
import sqlite3
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request

CATALOG_URL = "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv"
TEXT_URL = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"
USER_AGENT = "Diacritica-pipeline/1.0 (personal language-learning app; one-time download)"

# Authors who wrote in the language (surnames as they appear in the catalog's Authors field).
AUTHORS = {
    "en": ["Austen", "Dickens", "Doyle, Arthur Conan", "Wilde", "Twain", "Stevenson, Robert Louis", "Carroll, Lewis",
           "Montgomery, L. M.", "Alcott", "London, Jack", "Burnett, Frances Hodgson", "Kipling"],
    "fr": ["Maupassant", "Verne", "Dumas", "Hugo, Victor", "Flaubert", "Zola", "Daudet", "Malot", "Ségur", "Leblanc, Maurice",
           "Stendhal", "Mérimée"],
    "de": ["Spyri", "Storm, Theodor", "Kafka", "Fontane", "Hauff", "Schnitzler", "Keller, Gottfried", "Grimm",
           "Hoffmann, E. T. A.", "Raabe", "Ebner-Eschenbach", "Mann, Thomas"],
    "it": ["Collodi", "Verga", "De Amicis", "Pirandello", "Svevo", "Salgari", "Deledda", "Capuana", "Serao", "Fogazzaro",
           "Manzoni"],
    "es": ["Pérez Galdós", "Alarcón", "Valera, Juan", "Blasco Ibáñez", "Pardo Bazán", "Palacio Valdés", "Bécquer",
           "Unamuno", "Baroja", "Quiroga", "Pereda", "Clarín"],
}

LANG_NAMES = {"en": "english", "fr": "french", "de": "german", "it": "italian", "es": "spanish"}
LEVELS = [(5_000, "B1"), (12_000, "B2"), (30_000, "C1")]
WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*", re.UNICODE)
ABBREV = {
    "en": {"mr", "mrs", "ms", "dr", "st", "mt", "jr", "sr", "vs", "etc", "no", "capt", "col", "gen", "lt", "rev", "prof"},
    "fr": {"m", "mm", "mme", "mlle", "dr", "st", "ste", "etc", "cf", "p"},
    "de": {"hr", "fr", "dr", "st", "nr", "bzw", "usw", "z", "b", "d", "h", "u", "a", "s", "vgl", "ca"},
    "it": {"sig", "sigg", "sig.ra", "dott", "prof", "ecc", "s", "pag"},
    "es": {"sr", "sra", "srta", "d", "dña", "dr", "ud", "uds", "vd", "etc", "pág", "sto", "sta"},
}


def log(*a) -> None:
    print(*a, file=sys.stderr, flush=True)


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    except urllib.error.URLError as e:
        # python.org builds on macOS often lack root certificates; curl uses the system's.
        if "CERTIFICATE" not in str(e).upper():
            raise
        return subprocess.run(["curl", "-fsSL", "-A", USER_AGENT, url], check=True, capture_output=True).stdout


# ---------------------------------------------------------------- choosing books

def choose_books(cache: str, langs: list[str], per_lang: int, seed: int) -> list[tuple[str, int, str, str]]:
    """(lang, id, title, author) from the Gutenberg catalog, by the curated authors."""
    path = os.path.join(cache, "pg_catalog.csv")
    if not os.path.exists(path):
        log("downloading the Gutenberg catalog…")
        with open(path, "wb") as fh:
            fh.write(fetch(CATALOG_URL))
    rows = list(csv.DictReader(open(path, encoding="utf-8", newline="")))
    rnd = random.Random(seed)
    out = []
    for lang in langs:
        picks: dict[str, list[tuple[int, str, str]]] = {}
        for r in rows:
            if r.get("Type") != "Text" or (r.get("Language") or "").strip() != lang:
                continue
            authors = r.get("Authors") or ""
            title = (r.get("Title") or "").replace("\n", " ").strip()
            # Skip poetry, plays, letters, collected works and anything not prose fiction.
            low = (title + " " + (r.get("Subjects") or "")).lower()
            if any(k in low for k in ("poetry", "poems", "poesie", "gedichte", "poesías", "poesie", "drama", "théâtre",
                                      "comedia", "lettere", "letters", "briefe", "complete works", "œuvres complètes",
                                      "index", "dictionary", "wörterbuch")):
                continue
            for a in AUTHORS[lang]:
                if a.lower() in authors.lower():
                    picks.setdefault(a, []).append((int(r["Text#"]), title, authors.split(";")[0].strip()))
                    break
        chosen: list[tuple[int, str, str]] = []
        authors = list(picks.keys())
        rnd.shuffle(authors)
        # Round-robin over authors, at most two books each.
        for round_ in range(2):
            for a in authors:
                books = picks[a]
                rnd.shuffle(books)
                if len(books) > round_ and len(chosen) < per_lang:
                    chosen.append(books[round_])
        log(f"{lang}: {len(chosen)} books from {len(picks)} authors")
        out += [(lang, i, t, au) for i, t, au in chosen]
    return out


# ---------------------------------------------------------------- text → sentences

def strip_gutenberg(text: str) -> str:
    start = re.search(r"\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG.*?\*\*\*", text, re.I)
    end = re.search(r"\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG", text, re.I)
    return text[start.end() if start else 0: end.start() if end else len(text)]


def paragraphs(body: str) -> list[str]:
    out = []
    for block in re.split(r"\n\s*\n", body.replace("\r", "")):
        lines = [l.strip() for l in block.split("\n") if l.strip()]
        if not lines:
            continue
        p = " ".join(lines)
        p = re.sub(r"\[[^\]]{0,60}\]", " ", p)          # [Illustration], [12], [Footnote…]
        p = p.replace("_", "")                            # _italics_
        p = re.sub(r"\s+", " ", p).strip()
        # Headings and lists: short, all caps, or roman numerals.
        if len(p) < 30 or p.upper() == p or re.fullmatch(r"[IVXLC]+\.?", p):
            continue
        out.append(p)
    return out


def split_sentences(p: str, lang: str) -> list[str]:
    abbrev = ABBREV.get(lang, set())
    parts, buf = [], ""
    tokens = re.split(r"(?<=[.!?…])(\s+)", p)
    for i in range(0, len(tokens), 2):
        piece = tokens[i]
        buf += piece
        nxt = tokens[i + 2] if i + 2 < len(tokens) else ""
        last = re.findall(r"([^\W\d_]+)\.$", piece)
        is_abbrev = bool(last) and (last[0].lower() in abbrev or len(last[0]) == 1)
        starts_new = bool(re.match(r"[«\"“‘¿¡(—–-]*\s*[A-ZÀ-ÖØ-ÞÄÖÜ]", nxt))
        if nxt and (is_abbrev or not starts_new):
            buf += tokens[i + 1] if i + 1 < len(tokens) else " "
            continue
        parts.append(buf.strip())
        buf = ""
    if buf.strip():
        parts.append(buf.strip())
    return parts


def clean(s: str) -> str | None:
    s = s.strip()
    s = re.sub(r"^[—–\-]+\s*", "", s)                     # dialogue dashes
    s = s.replace("''", "\"").replace("``", "\"")
    # Drop a quote mark that wraps the whole sentence.
    if len(s) > 2 and s[0] in "\"“«‘'" and s[-1] in "\"”»’'" and sum(s.count(q) for q in "\"“”«»") <= 2:
        s = s[1:-1].strip()
    if not s or not s.lstrip("«\"“‘' ")[:1].isupper():
        return None
    if s[-1] not in ".!?…":
        return None
    for o, c in ("()", "[]", "«»", "“”"):
        if s.count(o) != s.count(c):
            return None
    if s.count("\"") % 2:
        return None
    if re.search(r"\d|[;:]|\.\.\.|…|[*#/@=<>|]", s):
        return None                                        # numbers, lists, ellipses, markup
    if sum(1 for ch in s if ch.isupper()) > len(s) * 0.15:
        return None
    return unicodedata.normalize("NFC", s)


def load_freq(path: str) -> dict[str, int]:
    ranks: dict[str, int] = {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for i, line in enumerate(fh):
            w = line.strip().split(" ")[0].lower()
            if w and w not in ranks:
                ranks[w] = i + 1
    return ranks


def level_for(s: str, lang: str, ranks: dict[str, int]) -> tuple[str | None, int, int]:
    words = WORD.findall(s)
    if not words:
        return None, 0, 0
    worst = 0
    for i, w in enumerate(words):
        # Names (capitalized mid-sentence) don't count, except in German where every noun is capitalized.
        if i > 0 and w[0].isupper() and lang != "de":
            continue
        lw = w.lower().replace("’", "'")
        r = ranks.get(lw)
        if r is None and "'" in lw:                       # l'homme, dell'acqua, don't
            pieces = [x for x in lw.split("'") if x]
            r = max((ranks.get(x, 10**9) for x in pieces), default=10**9)
        worst = max(worst, r if r is not None else 10**9)
    for limit, name in LEVELS:
        if worst <= limit:
            return name, worst, len(words)
    return None, worst, len(words)


# ---------------------------------------------------------------- build

def build(args: argparse.Namespace) -> None:
    cache = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "gutenberg")
    os.makedirs(cache, exist_ok=True)
    langs = [l.strip() for l in args.langs.split(",") if l.strip()]

    if args.ids:
        books = []
        for line in open(args.ids, encoding="utf-8"):
            parts = line.split()
            if len(parts) >= 2 and parts[0] in langs:
                books.append((parts[0], int(parts[1]), " ".join(parts[2:]) or f"Ebook {parts[1]}", ""))
    else:
        books = choose_books(cache, langs, args.books, args.seed)

    ranks = {l: load_freq(os.path.join(args.freq_dir, f"{l}_50k.txt")) for l in langs}
    rnd = random.Random(args.seed)
    rows = []
    for lang, bid, title, author in books:
        path = os.path.join(cache, f"{bid}.txt")
        if not os.path.exists(path):
            log(f"downloading {lang} #{bid} {title[:60]}…")
            try:
                data = fetch(TEXT_URL.format(id=bid))
            except Exception as e:  # noqa: BLE001
                log(f"  skipped: {e}")
                continue
            with open(path, "wb") as fh:
                fh.write(data)
            time.sleep(args.pause)
        text = open(path, encoding="utf-8", errors="replace").read()
        # The header says which language the file really is; skip mismatches.
        m = re.search(r"^Language:\s*(.+)$", text[:5000], re.M)
        if m and not m.group(1).strip().lower().startswith(LANG_NAMES[lang]):
            log(f"  #{bid} is not {lang} ({m.group(1).strip()}), skipped")
            continue
        if not author:
            am = re.search(r"^Author:\s*(.+)$", text[:5000], re.M)
            author = am.group(1).strip() if am else ""
        kept = 0
        seen = set()
        for p in paragraphs(strip_gutenberg(text)):
            for raw in split_sentences(p, lang):
                s = clean(raw)
                if not s or s in seen:
                    continue
                n = len(WORD.findall(s))
                if not (args.min_words <= n <= args.max_words):
                    continue
                level, worst, _ = level_for(s, lang, ranks[lang])
                if not level:
                    continue
                seen.add(s)
                rows.append((lang, s, level, n, worst, bid, title, author))
                kept += 1
        log(f"  #{bid} {title[:50]}: {kept} sentences")

    # Spread each language over levels and books.
    final = []
    for lang in langs:
        mine = [r for r in rows if r[0] == lang]
        by_level: dict[str, list] = {}
        for r in mine:
            by_level.setdefault(r[2], []).append(r)
        per_level = args.per_lang // 3
        for lvl, items in by_level.items():
            by_book: dict[int, list] = {}
            for r in items:
                by_book.setdefault(r[5], []).append(r)
            for b in by_book.values():
                rnd.shuffle(b)
            picked = []
            while len(picked) < per_level and any(by_book.values()):
                for b in list(by_book.values()):
                    if b and len(picked) < per_level:
                        picked.append(b.pop())
            final += picked
        counts = {l: sum(1 for r in final if r[0] == lang and r[2] == l) for l in ("B1", "B2", "C1")}
        log(f"{lang}: kept " + ", ".join(f"{k} {v}" for k, v in counts.items()))

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    if os.path.exists(args.out):
        os.remove(args.out)
    con = sqlite3.connect(args.out)
    con.executescript("""
        CREATE TABLE lit (id INTEGER PRIMARY KEY, lang TEXT NOT NULL, text TEXT NOT NULL, level TEXT NOT NULL,
                          words INTEGER, rank INTEGER, book INTEGER, title TEXT, author TEXT);
        CREATE INDEX lit_lang_level ON lit(lang, level);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """)
    con.executemany("INSERT INTO lit (lang, text, level, words, rank, book, title, author) VALUES (?,?,?,?,?,?,?,?)", final)
    con.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("built_at", time.strftime("%Y-%m-%d")), ("source", "Project Gutenberg (public domain)"),
        ("books", str(len({r[5] for r in final}))),
    ])
    con.commit()
    con.execute("VACUUM")
    con.close()
    log(f"wrote {len(final)} sentences to {args.out}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--freq-dir", required=True, help="folder with en_50k.txt, fr_50k.txt, de_50k.txt, it_50k.txt, es_50k.txt")
    p.add_argument("--out", required=True)
    p.add_argument("--langs", default="en,fr,de,it,es")
    p.add_argument("--books", type=int, default=8)
    p.add_argument("--per-lang", type=int, default=1500)
    p.add_argument("--min-words", type=int, default=6)
    p.add_argument("--max-words", type=int, default=20)
    p.add_argument("--ids", help='file with "lang ebook-number [title]" lines, instead of the catalog')
    p.add_argument("--pause", type=float, default=2.0, help="seconds between downloads")
    p.add_argument("--seed", type=int, default=7)
    build(p.parse_args())


if __name__ == "__main__":
    main()
