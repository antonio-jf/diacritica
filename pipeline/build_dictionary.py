#!/usr/bin/env python3
"""
Build Diacritica's offline dictionary (SQLite) from Wiktionary (Wiktextract/kaikki.org JSONL) and CC-CEDICT.

Run this on a computer, not the phone. The output file goes into
app/src/main/assets/dict/dictionary.db (bundled in the APK), or can be imported
in the app through Settings > Dictionary data > Import.

Inputs (all optional except --en; plain or .gz):
  --en      kaikki.org English-language extract of English Wiktionary (gives the
            English headwords and their translation tables: the cross-language links)
  --fr/--de/--it/--es
            kaikki.org extracts for those languages (gender, plurals, IPA, forms,
            English glosses, examples)
  --cedict  CC-CEDICT file (cedict_ts.u8 / cedict_1_0_ts_utf-8_mdbg.txt.gz)
  --hsk     optional TSV/CSV with "word<TAB or ,>level" (HSK badge)
  --top N --freq-dir DIR
            keep only the N most common words per language (smaller app). DIR holds frequency
            lists named en.txt, fr.txt, de.txt, it.txt, es.txt with one "word count" per line,
            most common first (e.g. github.com/hermitdave/FrequencyWords, files xx_50k.txt).
            Words missing from the trimmed dictionary are downloaded by the app when needed.

Example:
  python3 build_dictionary.py --en English.jsonl.gz --fr French.jsonl.gz \
      --de German.jsonl.gz --it Italian.jsonl.gz --es Spanish.jsonl.gz \
      --cedict cedict_1_0_ts_utf-8_mdbg.txt.gz --out dictionary.db
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import re
import sqlite3
import sys
import time
import unicodedata
from typing import Iterable, Iterator

SCHEMA_VERSION = 1
TARGET_LANGS = ("en", "fr", "de", "it", "es", "zh")
WIKT_CODES = {"fr": "fr", "de": "de", "it": "it", "es": "es", "cmn": "zh"}
SKIP_POS = {"name", "character", "symbol", "suffix", "prefix", "infix", "affix",
            "interfix", "circumfix", "punct", "romanization", "abbrev", "soft-redirect"}
JUNK_FORM_TAGS = {"table-tags", "inflection-template", "class", "romanization"}
MAX_SENSES = 8
MAX_FORMS = 80
MAX_EXAMPLE_LEN = 140

# ---------------------------------------------------------------- normalization
# Must stay identical to TextNorm.normalize() in the app (data/TextNorm.kt).
_SPECIAL = str.maketrans({"ß": "ss", "œ": "oe", "æ": "ae", "ø": "o", "ł": "l",
                          "đ": "d", "ı": "i", "’": "'", "ʼ": "'"})


def normalize(s: str) -> str:
    s = s.lower().translate(_SPECIAL)
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return " ".join(s.split())


# ---------------------------------------------------------------- pinyin
_MARKS = {
    "a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ",
}
_PINYIN_SYL = re.compile(r"^([a-zA-Zü:]+)([1-5])$")


def syllable_to_marks(syl: str) -> str:
    m = _PINYIN_SYL.match(syl)
    if not m:
        return syl.replace("u:", "ü").replace("U:", "Ü")
    base, tone = m.group(1).replace("u:", "ü").replace("U:", "Ü"), int(m.group(2))
    if tone == 5:
        return base
    low = base.lower()
    if "a" in low:
        idx = low.index("a")
    elif "e" in low:
        idx = low.index("e")
    elif "ou" in low:
        idx = low.index("o")
    else:
        idx = max((i for i, c in enumerate(low) if c in "aeiouü"), default=-1)
        if idx < 0:
            return base
    v = low[idx]
    marked = _MARKS[v][tone - 1]
    if base[idx].isupper():
        marked = marked.upper()
    return base[:idx] + marked + base[idx + 1:]


def pinyin_numbers_to_marks(pinyin: str) -> str:
    """'yao4 shi5' -> 'yàoshi', 'Xi1 an1' -> "Xī'ān", 'nu:3 er2' -> 'nǚ'ér'."""
    out: list[str] = []
    for syl in pinyin.split():
        marked = syllable_to_marks(syl)
        if not _PINYIN_SYL.match(syl):
            # punctuation like '·' or ',' or non-pinyin tokens: keep with spacing
            out.append(" " + marked + " " if marked not in ("·",) else marked)
            continue
        if out and marked[:1].lower() in "aeoāáǎàēéěèōóǒò" and not out[-1].endswith(" "):
            out.append("'")
        out.append(marked)
    return " ".join("".join(out).split())


def pinyin_compact_numbers(pinyin: str) -> str:
    """'Nu:3 er2' -> 'nu3er2' (lowercase, ü->u, no spaces) for numeric-tone search."""
    return "".join(pinyin.lower().replace("u:", "u").replace("ü", "u").split())


def pinyin_toneless(pinyin: str) -> str:
    return re.sub(r"[1-5]", "", pinyin_compact_numbers(pinyin))


# ---------------------------------------------------------------- IO helpers
def open_text(path: str) -> io.TextIOBase:
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8")
    return open(path, encoding="utf-8")


def iter_jsonl(path: str, limit: int | None) -> Iterator[dict]:
    with open_text(path) as fh:
        for n, line in enumerate(fh):
            if limit is not None and n >= limit:
                break
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def progress(label: str, n: int, t0: float) -> None:
    if n % 50000 == 0 and n:
        print(f"  {label}: {n:,} lines ({time.time() - t0:.0f}s)", file=sys.stderr)


# ---------------------------------------------------------------- schema
SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE concept (id INTEGER PRIMARY KEY, en_word TEXT NOT NULL, en_norm TEXT NOT NULL,
                      pos TEXT, label TEXT, rank INTEGER NOT NULL);
CREATE TABLE translation (concept_id INTEGER NOT NULL, lang TEXT NOT NULL, word TEXT NOT NULL,
                          norm TEXT NOT NULL, gender TEXT, roman TEXT, ord INTEGER NOT NULL);
CREATE TABLE entry (id INTEGER PRIMARY KEY, lang TEXT NOT NULL, word TEXT NOT NULL, norm TEXT NOT NULL,
                    pos TEXT, gender TEXT, ipa TEXT, plural TEXT);
CREATE TABLE sense (entry_id INTEGER NOT NULL, ord INTEGER NOT NULL, gloss TEXT, example TEXT,
                    example_en TEXT, form_of TEXT);
CREATE TABLE form (entry_id INTEGER NOT NULL, form TEXT NOT NULL, tags TEXT);
CREATE TABLE zh (id INTEGER PRIMARY KEY, simp TEXT NOT NULL, trad TEXT NOT NULL, pinyin TEXT NOT NULL,
                 pinyin_num TEXT NOT NULL, pinyin_norm TEXT NOT NULL, defs TEXT, measure TEXT, hsk INTEGER);
"""
INDEXES = """
CREATE INDEX idx_concept_norm ON concept(en_norm);
CREATE INDEX idx_tr_lang_norm ON translation(lang, norm);
CREATE INDEX idx_tr_concept ON translation(concept_id);
CREATE INDEX idx_entry_lang_norm ON entry(lang, norm);
CREATE INDEX idx_sense_entry ON sense(entry_id);
CREATE INDEX idx_form_entry ON form(entry_id);
CREATE INDEX idx_zh_simp ON zh(simp);
CREATE INDEX idx_zh_trad ON zh(trad);
CREATE INDEX idx_zh_pnorm ON zh(pinyin_norm);
CREATE INDEX idx_zh_pnum ON zh(pinyin_num);
"""


# ---------------------------------------------------------------- CC-CEDICT
_CEDICT_LINE = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$")
_CL_ITEM = re.compile(r"([^,\[\]]+)\[([^\]]+)\]")


def parse_measure(defn: str) -> str | None:
    """'CL:隻|只[zhi1],條|条[tiao2]' -> '只 zhī · 条 tiáo'"""
    items = []
    for chars, pin in _CL_ITEM.findall(defn[3:]):
        simp = chars.split("|")[-1].strip()
        items.append(f"{simp} {pinyin_numbers_to_marks(pin)}")
    return " · ".join(items) or None


def load_cedict(con: sqlite3.Connection, path: str, hsk: dict[str, int]) -> dict[str, tuple[str, str]]:
    """Returns a map simp/trad -> (simp, pinyin-with-marks) of the first reading, used to enrich translations."""
    t0 = time.time()
    lookup: dict[str, tuple[str, str]] = {}
    rows = []
    with open_text(path) as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            m = _CEDICT_LINE.match(line.strip())
            if not m:
                continue
            trad, simp, pin, defs_raw = m.groups()
            defs, measure = [], None
            for d in defs_raw.split("/"):
                d = d.strip()
                if not d:
                    continue
                if d.startswith("CL:"):
                    measure = parse_measure(d)
                else:
                    defs.append(d)
            marks = pinyin_numbers_to_marks(pin)
            rows.append((simp, trad, marks, pinyin_compact_numbers(pin), pinyin_toneless(pin),
                         " / ".join(defs), measure, hsk.get(simp) or hsk.get(trad)))
            lookup.setdefault(simp, (simp, marks))
            lookup.setdefault(trad, (simp, marks))
    con.executemany("INSERT INTO zh (simp, trad, pinyin, pinyin_num, pinyin_norm, defs, measure, hsk)"
                    " VALUES (?,?,?,?,?,?,?,?)", rows)
    print(f"CC-CEDICT: {len(rows):,} entries ({time.time() - t0:.0f}s)", file=sys.stderr)
    return lookup


def load_hsk(path: str | None) -> dict[str, int]:
    if not path:
        return {}
    out: dict[str, int] = {}
    with open_text(path) as fh:
        for line in fh:
            parts = re.split(r"[\t,;]", line.strip())
            if len(parts) >= 2 and parts[1].strip().isdigit():
                out.setdefault(parts[0].strip(), int(parts[1]))
    return out


# ---------------------------------------------------------------- Wiktextract helpers
def tr_lang(t: dict) -> str | None:
    code = t.get("lang_code") or t.get("code")
    if code in WIKT_CODES:
        return WIKT_CODES[code]
    if code == "zh" and "Mandarin" in (t.get("lang") or ""):
        return "zh"
    if (t.get("lang") or "") in ("Mandarin", "Chinese Mandarin"):
        return "zh"
    return None


_ZH_PARENS = re.compile(r"[(（][^)）]*[)）]")


def clean_zh(raw: str) -> tuple[str, str | None] | None:
    """Wiktionary writes Mandarin as "trad /simp" ("老師 /老师"), sometimes with notes ("(一)劑 /剂").
    Returns (simplified, traditional or None). Mirrors ZhClean.split() in the app."""
    parts = [_ZH_PARENS.sub("", p).strip().strip("-….").strip() for p in raw.split("/")]
    parts = [p for p in parts if re.search(r"[\u3400-\u9fff]", p)]
    if not parts:
        return None
    simp = parts[-1]
    trad = parts[0] if parts[0] != simp and len(parts[0]) == len(simp) else None
    return simp, trad


def gender_from_tags(tags: Iterable[str]) -> str | None:
    tags = set(tags or [])
    g = [x for t, x in (("masculine", "m"), ("feminine", "f"), ("neuter", "n"), ("common", "c")) if t in tags]
    if not g:
        return None
    s = "/".join(g)
    if "plural" in tags and "singular" not in tags:
        s += " pl"
    return s


_HEAD_GENDER = re.compile(r"^\s+(m|f|n|c)(?:\s*(?:or|,|/)\s*(m|f|n))?(\s+pl\b)?(?=\s|$|\()")


def entry_gender(e: dict) -> str | None:
    g = gender_from_tags(e.get("tags"))
    if g:
        return g
    for ht in e.get("head_templates") or []:
        exp, word = ht.get("expansion") or "", e.get("word") or ""
        if exp.startswith(word):
            m = _HEAD_GENDER.match(exp[len(word):])
            if m:
                s = m.group(1) + ("/" + m.group(2) if m.group(2) else "")
                return s + (" pl" if m.group(3) else "")
    senses = e.get("senses") or []
    if senses:
        return gender_from_tags(senses[0].get("tags"))
    return None


_ARTICLES = {"der", "die", "das", "den", "dem", "des", "le", "la", "les", "l'", "il", "lo", "gli", "i",
             "el", "los", "las"}


def strip_article(form: str) -> str:
    parts = form.split(" ", 1)
    if len(parts) == 2 and parts[0].lower() in _ARTICLES:
        return parts[1]
    return form


def clean_forms(e: dict) -> list[tuple[str, str]]:
    out, seen = [], set()
    word = e.get("word")
    for f in e.get("forms") or []:
        form, tags = (f.get("form") or "").strip(), [t for t in (f.get("tags") or [])]
        if not form or form == word and not tags or set(tags) & JUNK_FORM_TAGS or form in ("-", "—"):
            continue
        key = (form, ",".join(tags))
        if key in seen:
            continue
        seen.add(key)
        out.append(key)
        if len(out) >= MAX_FORMS:
            break
    return out


def entry_plural(forms: list[tuple[str, str]], lang: str) -> str | None:
    best = None
    for form, tags in forms:
        ts = set(tags.split(","))
        if "plural" not in ts:
            continue
        if lang == "de":
            if "nominative" in ts and "definite" not in ts and "indefinite" not in ts:
                return strip_article(form)
            if best is None and "nominative" in ts:
                best = strip_article(form)
        elif ts <= {"plural", "masculine", "feminine"} and best is None:
            best = strip_article(form)
        elif best is None and len(ts) <= 2:
            best = strip_article(form)
    return best


def first_ipa(e: dict) -> str | None:
    for s in e.get("sounds") or []:
        if s.get("ipa"):
            return s["ipa"]
    return None


def sense_rows(e: dict) -> list[tuple[int, str, str | None, str | None, str | None]]:
    rows = []
    for i, s in enumerate((e.get("senses") or [])[:MAX_SENSES]):
        gloss = "; ".join(s.get("glosses") or s.get("raw_glosses") or [])
        if not gloss:
            continue
        ex, ex_en = None, None
        for x in s.get("examples") or []:
            text = (x.get("text") or "").strip()
            if text and len(text) <= MAX_EXAMPLE_LEN:
                ex, ex_en = text, (x.get("english") or x.get("translation") or None)
                break
        form_of = None
        for key in ("form_of", "alt_of"):
            if s.get(key):
                form_of = s[key][0].get("word")
                break
        rows.append((i, gloss, ex, ex_en, form_of))
    return rows


# ---------------------------------------------------------------- builders
def load_frequency(freq_dir: str | None, top: int | None) -> dict[str, set[str]]:
    """Top-N normalized words per language; empty dict = keep everything."""
    if not freq_dir or not top:
        return {}
    import os
    out: dict[str, set[str]] = {}
    for lang in ("en", "fr", "de", "it", "es"):
        import glob
        matches = [os.path.join(freq_dir, f"{lang}.txt")] + sorted(glob.glob(os.path.join(freq_dir, f"{lang}_*.txt")))
        path = next((m for m in matches if os.path.exists(m)), None)
        if not path:
            continue
        words: set[str] = set()
        with open_text(path) as fh:
            for line in fh:
                w = line.strip().split(" ")[0] if line.strip() else ""
                if w:
                    words.add(normalize(w))
                if len(words) >= top:
                    break
        out[lang] = words
        print(f"frequency {lang}: keeping top {len(words):,}", file=sys.stderr)
    return out


class Builder:
    def __init__(self, con: sqlite3.Connection, zh_lookup: dict[str, tuple[str, str]],
                 keep: dict[str, set[str]] | None = None):
        self.con = con
        self.zh_lookup = zh_lookup
        self.next_concept = 1
        self.next_entry = 1
        self.rank: dict[str, int] = {}
        # Trimming: keep[lang] = allowed normalized words; translations of kept English words are added.
        self.keep = keep or {}
        self.linked: dict[str, set[str]] = {}

    def allowed(self, lang: str, word: str) -> bool:
        if lang not in self.keep:
            return True
        n = normalize(word)
        return n in self.keep[lang] or n in self.linked.get(lang, set())

    def add_entry(self, lang: str, e: dict) -> None:
        word = (e.get("word") or "").strip()
        pos = e.get("pos")
        if not word or pos in SKIP_POS or not self.allowed(lang, word):
            return
        senses = sense_rows(e)
        if not senses:
            return
        forms = clean_forms(e)
        eid = self.next_entry
        self.next_entry += 1
        self.con.execute("INSERT INTO entry VALUES (?,?,?,?,?,?,?,?)",
                         (eid, lang, word, normalize(word), pos, entry_gender(e), first_ipa(e),
                          entry_plural(forms, lang)))
        self.con.executemany("INSERT INTO sense VALUES (?,?,?,?,?,?)", [(eid, *r) for r in senses])
        if forms:
            self.con.executemany("INSERT INTO form VALUES (?,?,?)", [(eid, f, t) for f, t in forms])

    def add_concepts(self, e: dict) -> None:
        word = (e.get("word") or "").strip()
        pos = e.get("pos")
        if not word or pos in SKIP_POS or not self.allowed("en", word):
            return
        groups: dict[str, list[dict]] = {}
        order: list[str] = []

        def collect(trs: list[dict], fallback_label: str | None) -> None:
            for t in trs or []:
                lang = tr_lang(t)
                tw = (t.get("word") or "").strip()
                if not lang or not tw:
                    continue
                label = (t.get("sense") or fallback_label or "").strip()
                if label not in groups:
                    groups[label] = []
                    order.append(label)
                groups[label].append({**t, "_lang": lang, "word": tw})

        collect(e.get("translations"), None)
        for s in e.get("senses") or []:
            if s.get("translations"):
                collect(s["translations"], "; ".join(s.get("glosses") or [])[:80] or None)
        if not groups:
            return
        norm = normalize(word)
        for label in order:
            cid = self.next_concept
            self.next_concept += 1
            rank = self.rank.get(norm, 0)
            self.rank[norm] = rank + 1
            self.con.execute("INSERT INTO concept VALUES (?,?,?,?,?,?)", (cid, word, norm, pos, label, rank))
            seen, rows = set(), []
            for i, t in enumerate(groups[label]):
                lang, tw, roman = t["_lang"], t["word"], t.get("roman")
                if lang == "zh":
                    cz = clean_zh(tw)
                    if not cz:
                        continue
                    simp, marks = self.zh_lookup.get(cz[0], (cz[0], None))
                    tw, roman = simp, roman or marks
                key = (lang, tw)
                if key in seen:
                    continue
                seen.add(key)
                self.linked.setdefault(lang, set()).add(normalize(tw))
                rows.append((cid, lang, tw, normalize(tw), gender_from_tags(t.get("tags")), roman, i))
            self.con.executemany("INSERT INTO translation VALUES (?,?,?,?,?,?,?)", rows)


_TOP_LANG = re.compile(r'"lang_code": ?"(en|fr|de|it|es)"')


def build_from_raw(args: argparse.Namespace, b: "Builder") -> list[str]:
    """
    Single combined kaikki file (raw-wiktextract-data.jsonl.gz, all languages).
    Pass 1: English entries are imported (concepts + translations) and French/German/Italian/
    Spanish lines are set aside in temporary files. Pass 2: those entries are imported, so the
    common-words filter already knows which words are translations of kept English words.
    """
    import os
    import tempfile
    tmpdir = tempfile.mkdtemp(prefix="diacritica-")
    outs = {lng: gzip.open(os.path.join(tmpdir, f"{lng}.jsonl.gz"), "wt", encoding="utf-8", compresslevel=1)
            for lng in ("fr", "de", "it", "es")}
    t0, kept = time.time(), {lng: 0 for lng in outs}
    with open_text(args.raw) as fh:
        for n, line in enumerate(fh):
            if args.limit is not None and n >= args.limit:
                break
            progress("raw", n, t0)
            if not _TOP_LANG.search(line):
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            code = e.get("lang_code")
            if code == "en":
                b.add_concepts(e)
                b.add_entry("en", e)
            elif code in outs:
                outs[code].write(line if line.endswith("\n") else line + "\n")
                kept[code] += 1
    for f in outs.values():
        f.close()
    print(f"English: {b.next_concept - 1:,} concepts; set aside {kept}", file=sys.stderr)
    for lng in outs:
        path = os.path.join(tmpdir, f"{lng}.jsonl.gz")
        t0, before = time.time(), b.next_entry
        for n, e in enumerate(iter_jsonl(path, None)):
            progress(lng, n, t0)
            b.add_entry(lng, e)
        print(f"{lng}: {b.next_entry - before:,} entries", file=sys.stderr)
        os.remove(path)
    os.rmdir(tmpdir)
    return [lng for lng in outs if kept[lng]]


def build(args: argparse.Namespace) -> None:
    con = sqlite3.connect(args.out)
    con.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    for t in ("meta", "concept", "translation", "entry", "sense", "form", "zh"):
        con.execute(f"DROP TABLE IF EXISTS {t}")
    con.executescript(SCHEMA)

    hsk = load_hsk(args.hsk)
    zh_lookup = load_cedict(con, args.cedict, hsk) if args.cedict else {}
    b = Builder(con, zh_lookup, load_frequency(args.freq_dir, args.top))

    raw_langs: list[str] = []
    if args.raw:
        raw_langs = build_from_raw(args, b)
    else:
        t0 = time.time()
        for n, e in enumerate(iter_jsonl(args.en, args.limit)):
            progress("en", n, t0)
            if e.get("lang_code", "en") != "en":
                continue
            b.add_concepts(e)
            b.add_entry("en", e)
        print(f"English: {b.next_concept - 1:,} concepts", file=sys.stderr)

    for lang in ("fr", "de", "it", "es"):
        path = getattr(args, lang)
        if not path or args.raw:
            continue
        t0, count = time.time(), b.next_entry
        for n, e in enumerate(iter_jsonl(path, args.limit)):
            progress(lang, n, t0)
            if e.get("lang_code", lang) != lang:
                continue
            b.add_entry(lang, e)
        print(f"{lang}: {b.next_entry - count:,} entries", file=sys.stderr)

    langs = ["en"] + [lng for lng in ("fr", "de", "it", "es") if getattr(args, lng) or lng in raw_langs] + (["zh"] if args.cedict else [])
    meta = {"schema_version": str(SCHEMA_VERSION), "built_at": time.strftime("%Y-%m-%d"),
            "languages": ",".join(langs), "label": args.label,
            "sources": "Wiktionary via Wiktextract (CC BY-SA); CC-CEDICT (CC BY-SA)",
            "zh_clean": "1"}
    con.executemany("INSERT INTO meta VALUES (?,?)", meta.items())
    print("Indexing…", file=sys.stderr)
    con.executescript(INDEXES)
    con.commit()
    con.execute("VACUUM")
    con.close()
    print(f"Done: {args.out}", file=sys.stderr)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--en", help="English extract (per-language files)")
    p.add_argument("--raw", help="combined raw-wiktextract-data.jsonl(.gz) with all languages (replaces --en/--fr/...)")
    for lang in ("fr", "de", "it", "es"):
        p.add_argument(f"--{lang}")
    p.add_argument("--cedict")
    p.add_argument("--hsk")
    p.add_argument("--out", default="dictionary.db")
    p.add_argument("--label", default="Full dictionary")
    p.add_argument("--limit", type=int, help="only read the first N lines of each file (testing)")
    p.add_argument("--top", type=int, help="keep only the N most common words per language (needs --freq-dir)")
    p.add_argument("--freq-dir", help="folder with en.txt, fr.txt, de.txt, it.txt, es.txt frequency lists")
    args = p.parse_args()
    if not args.en and not args.raw:
        p.error("give --raw (combined file) or --en (+ --fr/--de/--it/--es)")
    build(args)


if __name__ == "__main__":
    main()
