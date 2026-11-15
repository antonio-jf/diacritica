#!/usr/bin/env python3
"""
Build Diacritica's News, Formal and Encyclopedic sentences from OPUS (https://opus.nlpl.eu),
the open collection of human-translated, sentence-aligned texts.

  news          Global Voices: citizen journalism translated by volunteers (CC BY 3.0)
  formal        Europarl: European Parliament proceedings (free to use; no Mandarin)
  encyclopedic  Wikimedia: Wikipedia articles translated by editors (CC BY-SA)

English is the pivot: for each corpus the EN–FR, EN–DE, EN–IT, EN–ES and EN–ZH files are read and
sentences with the same English text are grouped, so one group holds the human translations into
every language that has it. Levels come from the rarest English word in en_50k.txt, as for Tatoeba:
A2 ≤ 2,000, B1 ≤ 5,000, B2 ≤ 12,000, C1 ≤ 30,000.

Run on your computer (downloads are cached in data/opus/; Europarl is the big one, ~200 MB per pair):
  cd pipeline
  python3 build_corpora.py --freq data/en_50k.txt --out ../app/src/main/assets/corpora/corpora.db

Options:
  --corpora news,formal,encyclopedic   which ones (default: all three)
  --per-corpus N                       sentence groups kept per corpus (default 3000)
"""
from __future__ import annotations

import argparse
import io
import json
import os
import random
import re
import sqlite3
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

USER_AGENT = "Diacritica-pipeline/1.0 (personal language-learning app)"
LANGS = ["fr", "de", "it", "es", "zh"]
# Tests set this so missing pairs are skipped instead of downloaded.
OFFLINE = False
# Languages a corpus doesn't have (Europarl is EU languages only), so they aren't even tried.
MISSING = {"formal": {"zh"}}
# Some corpora use other codes for Mandarin (Global Voices: zhs = simplified, zht = traditional).
CODES = {"zh": ["zh", "zhs", "zh_cn", "zh_CN", "cmn", "zht"]}

# OPUS corpus name and a known release; the OPUS API is asked for the latest URL first.
CORPORA = {
    "news": ("GlobalVoices", "v2018q4", "Global Voices (CC BY 3.0)"),
    "formal": ("Europarl", "v8", "Europarl"),
    "encyclopedic": ("wikimedia", "v20230407", "Wikipedia translations (CC BY-SA)"),
}
LEVELS = [(2_000, "A2"), (5_000, "B1"), (12_000, "B2"), (30_000, "C1")]
WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*", re.UNICODE)
# Lines that are procedure or boilerplate rather than sentences to translate.
SKIP_START = re.compile(r"^(mr|mrs|madam|madame) (president|commissioner)|^(question|report|vote|explanation) |^\(", re.I)


def log(*a) -> None:
    print(*a, file=sys.stderr, flush=True)


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        # Older Pythons don't follow 307/308 redirects (the OPUS API answers with 308).
        if e.code in (307, 308) and e.headers.get("Location"):
            return fetch(urllib.parse.urljoin(url, e.headers["Location"]))
        raise
    except urllib.error.URLError as e:
        # python.org builds on macOS often lack root certificates; curl uses the system's.
        if "CERTIFICATE" not in str(e).upper():
            raise
        return subprocess.run(["curl", "-fsSL", "-A", USER_AGENT, url], check=True, capture_output=True).stdout


def pair(lang: str) -> str:
    """OPUS names pairs alphabetically: de-en, en-es, en-fr, en-it, en-zh (en-zhs…)."""
    return "-".join(sorted(["en", lang]))


def download_any(cache: str, corpus: str, version: str, lang: str) -> tuple[str, str] | None:
    """Tries each code a corpus may use for [lang]; returns (zip path, code used)."""
    for code in CODES.get(lang, [lang]):
        path = download(cache, corpus, version, code, quiet=code != CODES.get(lang, [lang])[-1])
        if path:
            return path, code
    return None


def moses_url(corpus: str, version: str, lang: str) -> str:
    a, b = pair(lang).split("-")
    api = f"https://opus.nlpl.eu/opusapi/?source={a}&target={b}&corpus={corpus}&preprocessing=moses&version=latest"
    try:
        data = json.loads(fetch(api))
        urls = [c["url"] for c in data.get("corpora", []) if c.get("url", "").endswith(".zip")]
        if urls:
            return urls[0]
    except Exception as e:  # noqa: BLE001
        log(f"  (OPUS API unavailable: {e}; using the known release)")
    return f"https://object.pouta.csc.fi/OPUS-{corpus}/{version}/moses/{pair(lang)}.txt.zip"


def download(cache: str, corpus: str, version: str, lang: str, quiet: bool = False) -> str | None:
    path = os.path.join(cache, f"{corpus}.{pair(lang)}.zip")
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    if OFFLINE:
        return None
    url = moses_url(corpus, version, lang)
    log(f"downloading {url}")
    try:
        data = fetch(url)
    except Exception as e:  # noqa: BLE001
        log(f"  {'(trying another code) ' if quiet else 'skipped '}{corpus} {pair(lang)}: {e}")
        return None
    with open(path, "wb") as fh:
        fh.write(data)
    time.sleep(1)
    return path


def lines(zf: zipfile.ZipFile, lang: str):
    names = [n for n in zf.namelist() if n.endswith("." + lang) and not n.upper().startswith(("README", "LICENSE"))]
    if not names:
        raise ValueError(f"no .{lang} file in {zf.filename}")
    with zf.open(names[0]) as fh:
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            yield raw.rstrip("\n")


# ---------------------------------------------------------------- filters and levels

def good_english(s: str, min_words: int, max_words: int) -> bool:
    if not s or not s[0].isupper() or s[-1] not in ".!?":
        return False
    n = len(WORD.findall(s))
    if not (min_words <= n <= max_words):
        return False
    if SKIP_START.search(s) or re.search(r"https?://|www\.|@|[\[\]{}<>|#*_=/\\]|\.\.\.|…", s):
        return False
    if sum(ch.isdigit() for ch in s) > 4:
        return False
    if sum(ch.isupper() for ch in s) > len(s) * 0.15:
        return False
    for o, c in ("()", "“”"):
        if s.count(o) != s.count(c):
            return False
    return s.count('"') % 2 == 0


def good_translation(t: str, lang: str, en: str) -> bool:
    if not t or t.strip() == en.strip():
        return False
    if lang == "zh":
        # Mostly Han characters, not English left untranslated.
        han = sum(1 for ch in t if "一" <= ch <= "鿿")
        return han >= 4 and han >= len(t) * 0.4
    ratio = len(t) / max(1, len(en))
    return 0.5 <= ratio <= 2.0


def load_freq(path: str) -> dict[str, int]:
    ranks: dict[str, int] = {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for i, line in enumerate(fh):
            w = line.strip().split(" ")[0].lower()
            if w and w not in ranks:
                ranks[w] = i + 1
    return ranks


def level_for(s: str, ranks: dict[str, int]) -> str | None:
    worst = 0
    for i, w in enumerate(WORD.findall(s)):
        if i > 0 and w[0].isupper():
            continue                                      # names
        lw = w.lower().replace("’", "'")
        r = ranks.get(lw) or ranks.get(lw.split("'")[0]) or 10**9
        worst = max(worst, r)
    for limit, name in LEVELS:
        if worst <= limit:
            return name
    return None


def norm(s: str) -> str:
    return unicodedata.normalize("NFC", re.sub(r"\s+", " ", s).strip())


# ---------------------------------------------------------------- build

def build_corpus(key: str, args, ranks, cache) -> list[tuple[str, str, dict[str, str]]]:
    corpus, version, _label = CORPORA[key]
    # Hash sampling keeps the same English sentences across all pairs, so translations line up.
    keep = max(1, int(args.sample * 1000))
    groups: dict[str, dict[str, str]] = {}
    levels: dict[str, str] = {}
    # Each language may start its own share of new groups, so the last one read (Mandarin) isn't crowded out.
    quota = max(1, args.max_candidates // len(LANGS))
    for lang in LANGS:
        if lang in MISSING.get(key, set()):
            continue
        found = download_any(cache, corpus, version, lang)
        if not found:
            continue
        path, code = found
        started = 0
        try:
            zf = zipfile.ZipFile(path)
            en_iter, x_iter = lines(zf, "en"), lines(zf, code)
        except (zipfile.BadZipFile, ValueError) as e:
            log(f"  {path}: {e}")
            continue
        added = 0
        for en, x in zip(en_iter, x_iter):
            en, x = norm(en), norm(x)
            if zlib.crc32(en.encode()) % 1000 >= keep:
                continue
            if en not in groups:
                if started >= quota or not good_english(en, args.min_words, args.max_words):
                    continue
                if not good_translation(x, lang, en):
                    continue
                lvl = level_for(en, ranks)
                if not lvl:
                    continue
                groups[en] = {}
                levels[en] = lvl
                started += 1
            if lang not in groups[en] and good_translation(x, lang, en):
                groups[en][lang] = x
                added += 1
        log(f"  {corpus} en-{lang}: {added:,} translations")
    # Keep groups with at least --min-langs translations, spread over levels, preferring more languages.
    # Mandarin rarely shares an English sentence with the others, so English–Mandarin pairs are kept too,
    # up to a quarter of each level.
    rnd = random.Random(args.seed)
    by_level: dict[str, list[str]] = {}
    for en, tr in groups.items():
        if len(tr) >= args.min_langs or "zh" in tr:
            by_level.setdefault(levels[en], []).append(en)
    out = []
    per_level = args.per_corpus // max(1, len(by_level))
    for lvl, ens in sorted(by_level.items()):
        rnd.shuffle(ens)
        ens.sort(key=lambda e: -len(groups[e]))
        zh = [e for e in ens if "zh" in groups[e]][: per_level // 4]
        rest = [e for e in ens if e not in set(zh) and len(groups[e]) >= args.min_langs][: per_level - len(zh)]
        for en in zh + rest:
            out.append((en, lvl, groups[en]))
    per_lang = {l: sum(1 for _, _, tr in out if l in tr) for l in LANGS}
    log(f"{key}: kept {len(out):,} groups; translations: " + ", ".join(f"{l} {n:,}" for l, n in per_lang.items()))
    return out


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--freq", required=True, help="English frequency list (data/en_50k.txt)")
    p.add_argument("--out", required=True)
    p.add_argument("--corpora", default="news,formal,encyclopedic")
    p.add_argument("--per-corpus", type=int, default=3000)
    p.add_argument("--min-words", type=int, default=6)
    p.add_argument("--max-words", type=int, default=22)
    p.add_argument("--min-langs", type=int, default=2, help="translations needed besides English")
    p.add_argument("--sample", type=float, default=0.25, help="share of English sentences considered (speed/memory)")
    p.add_argument("--max-candidates", type=int, default=400_000)
    p.add_argument("--seed", type=int, default=7)
    args = p.parse_args()

    cache = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "opus")
    os.makedirs(cache, exist_ok=True)
    ranks = load_freq(args.freq)
    keys = [k.strip() for k in args.corpora.split(",") if k.strip()]
    for k in keys:
        if k not in CORPORA:
            p.error(f"unknown corpus {k!r}; choose from {', '.join(CORPORA)}")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    if os.path.exists(args.out):
        os.remove(args.out)
    con = sqlite3.connect(args.out)
    con.executescript("""
        CREATE TABLE sgroup (id INTEGER PRIMARY KEY, level TEXT NOT NULL, langs INTEGER NOT NULL, source TEXT NOT NULL);
        CREATE TABLE stext (group_id INTEGER NOT NULL, lang TEXT NOT NULL, ord INTEGER NOT NULL, text TEXT NOT NULL);
        CREATE INDEX stext_group ON stext(group_id);
        CREATE INDEX sgroup_source_level ON sgroup(source, level);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """)
    total = 0
    for k in keys:
        log(f"== {k} ({CORPORA[k][0]})")
        for en, lvl, tr in build_corpus(k, args, ranks, cache):
            cur = con.execute("INSERT INTO sgroup (level, langs, source) VALUES (?, ?, ?)", (lvl, len(tr), k))
            gid = cur.lastrowid
            con.execute("INSERT INTO stext VALUES (?, 'en', 0, ?)", (gid, en))
            con.executemany("INSERT INTO stext VALUES (?, ?, 0, ?)", [(gid, l, t) for l, t in tr.items()])
            total += 1
    con.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("built_at", time.strftime("%Y-%m-%d")),
        ("sources", "; ".join(f"{k}: {CORPORA[k][2]}" for k in keys)),
    ])
    con.commit()
    con.execute("VACUUM")
    con.close()
    log(f"wrote {total:,} sentence groups to {args.out}")


if __name__ == "__main__":
    main()
