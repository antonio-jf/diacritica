#!/usr/bin/env python3
"""
Build Diacritica's parallel classics (Reader › Classics) from the OPUS "Books" corpus:
public-domain novels aligned sentence by sentence across languages (https://opus.nlpl.eu/Books).

For each language pair (en-fr, de-en, …) the script downloads the Moses text files. The zip also
has a .ids file with the source documents of every line ("en/Austen_Jane-Pride_and_Prejudice.xml.gz
<tab> fr/…"), which splits the lines back into books, with title and author taken from the file names
(→ Jane Austen, Pride and Prejudice). Zips with an XCES alignment file (.xml) instead are handled too.

Run on your computer (downloads are cached in data/opus/):
  cd pipeline
  python3 build_books.py --out ../app/src/main/assets/books/books.db

Then publish it as a data pack (make_packs.py --books …) or keep it bundled for your own builds.

Options:
  --pairs en-fr,de-en,…   language pairs (default: every pair of EN/ES/FR/DE/IT that OPUS has)
  --min-sentences N        skip very short texts (default 150)
"""
from __future__ import annotations

import argparse
import io
import itertools
import os
import re
import sqlite3
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

import build_corpora  # shared download helper (fetch with redirects and the macOS certificate fallback)

LANGS = ["en", "es", "fr", "de", "it"]
CORPUS, VERSION = "Books", "v1"
URL = "https://object.pouta.csc.fi/OPUS-{corpus}/{version}/moses/{pair}.txt.zip"


def log(*a) -> None:
    print(*a, file=sys.stderr, flush=True)


def all_pairs() -> list[str]:
    return ["-".join(sorted(p)) for p in itertools.combinations(LANGS, 2)]


def download(cache: str, pair: str) -> str | None:
    path = os.path.join(cache, f"{CORPUS}.{pair}.zip")
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    if build_corpora.OFFLINE:
        return None
    url = URL.format(corpus=CORPUS, version=VERSION, pair=pair)
    log(f"downloading {url}")
    try:
        data = build_corpora.fetch(url)
    except Exception as e:  # noqa: BLE001
        log(f"  skipped {pair}: {e}")
        return None
    with open(path, "wb") as fh:
        fh.write(data)
    time.sleep(1)
    return path


def title_author(doc: str) -> tuple[str, str]:
    """'en/Austen_Jane-Pride_and_Prejudice.xml.gz' → ('Pride and Prejudice', 'Jane Austen')."""
    name = os.path.basename(doc)
    name = re.sub(r"\.xml(\.gz)?$", "", name)
    if "-" in name:
        who, what = name.split("-", 1)
    else:
        who, what = "", name
    parts = [p for p in who.split("_") if p]
    author = " ".join(parts[1:] + parts[:1]) if len(parts) >= 2 else " ".join(parts)
    title = what.replace("_", " ").strip()
    return title, author


def books_from_ids(zf: zipfile.ZipFile) -> list[tuple[str, str, int]] | None:
    """(fromDoc, toDoc, number of lines) per book from the .ids file: one line per Moses line, in order."""
    ids = [n for n in zf.namelist() if n.endswith(".ids")]
    if not ids:
        return None
    out: list[list] = []
    with zf.open(ids[0]) as fh:
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            parts = raw.rstrip("\n").split("\t")
            doc = (parts[0], parts[1] if len(parts) > 1 else "")
            if out and (out[-1][0], out[-1][1]) == doc:
                out[-1][2] += 1
            else:
                out.append([doc[0], doc[1], 1])
    return [tuple(x) for x in out]


def books_in(zf: zipfile.ZipFile) -> list[tuple[str, str, int]]:
    """(fromDoc, toDoc, number of Moses lines) for each book: from the .ids file, else the XCES alignment file."""
    from_ids = books_from_ids(zf)
    if from_ids is not None:
        return from_ids
    xml = [n for n in zf.namelist() if n.endswith(".xml")]
    if not xml:
        raise ValueError("no .ids or alignment (.xml) file in the zip")
    out = []
    cur = None
    with zf.open(xml[0]) as fh:
        for event, el in ET.iterparse(fh, events=("start", "end")):
            if event == "start" and el.tag == "linkGrp":
                cur = [el.get("fromDoc", ""), el.get("toDoc", ""), 0]
            elif event == "end" and el.tag == "link" and cur is not None:
                src, _, tgt = (el.get("xtargets") or "").partition(";")
                if src.strip() and tgt.strip():
                    cur[2] += 1
                el.clear()
            elif event == "end" and el.tag == "linkGrp" and cur is not None:
                out.append(tuple(cur))
                cur = None
                el.clear()
    return out


def lines(zf: zipfile.ZipFile, lang: str):
    names = [n for n in zf.namelist() if n.endswith("." + lang)]
    if not names:
        raise ValueError(f"no .{lang} file")
    with zf.open(names[0]) as fh:
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            yield unicodedata.normalize("NFC", re.sub(r"\s+", " ", raw).strip())


def build_pair(pair: str, cache: str, min_sentences: int) -> list[dict]:
    path = download(cache, pair)
    if not path:
        return []
    a, b = pair.split("-")
    try:
        zf = zipfile.ZipFile(path)
        groups = books_in(zf)
        la, lb = list(lines(zf, a)), list(lines(zf, b))
    except (zipfile.BadZipFile, ValueError, ET.ParseError) as e:
        log(f"  {pair}: {e}")
        return []
    total = sum(n for _, _, n in groups)
    if total != len(la) or len(la) != len(lb):
        # Never guess: a mismatch would put sentences into the wrong books.
        log(f"  {pair}: alignment has {total:,} links but the text files have {len(la):,}/{len(lb):,} lines; skipped")
        return []
    out = []
    pos = 0
    for from_doc, to_doc, n in groups:
        sa, sb = la[pos:pos + n], lb[pos:pos + n]
        pos += n
        pairs = [(x, y) for x, y in zip(sa, sb) if x and y]
        if len(pairs) < min_sentences:
            continue
        # fromDoc belongs to the first language of the pair name.
        doc_a = from_doc if ("/" + from_doc).find(f"/{a}/") >= 0 else to_doc
        title, author = title_author(doc_a)
        out.append(dict(lang_a=a, lang_b=b, title=title, author=author, sentences=pairs))
    log(f"  {pair}: {len(out)} books, {sum(len(x['sentences']) for x in out):,} sentence pairs")
    return out


def write(out: str, books: list[dict]) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)
    con = sqlite3.connect(out)
    con.executescript("""
        CREATE TABLE book (id INTEGER PRIMARY KEY, lang_a TEXT NOT NULL, lang_b TEXT NOT NULL, title TEXT NOT NULL,
                           author TEXT NOT NULL, sentences INTEGER NOT NULL, words INTEGER NOT NULL);
        CREATE TABLE bsent (book_id INTEGER NOT NULL, idx INTEGER NOT NULL, a TEXT NOT NULL, b TEXT NOT NULL,
                            PRIMARY KEY (book_id, idx));
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """)
    for bk in sorted(books, key=lambda x: (x["author"], x["title"], x["lang_a"], x["lang_b"])):
        words = sum(len(x.split()) for x, _ in bk["sentences"])
        cur = con.execute("INSERT INTO book (lang_a, lang_b, title, author, sentences, words) VALUES (?,?,?,?,?,?)",
                          (bk["lang_a"], bk["lang_b"], bk["title"], bk["author"], len(bk["sentences"]), words))
        con.executemany("INSERT INTO bsent VALUES (?,?,?,?)",
                        [(cur.lastrowid, i, x, y) for i, (x, y) in enumerate(bk["sentences"])])
    con.executemany("INSERT INTO meta VALUES (?,?)", [
        ("built_at", time.strftime("%Y-%m-%d")),
        ("source", "OPUS Books (public-domain books aligned by András Farkas)"),
        ("languages", ",".join(sorted({b["lang_a"] for b in books} | {b["lang_b"] for b in books}))),
    ])
    con.commit()
    con.execute("VACUUM")
    con.close()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True)
    p.add_argument("--pairs", default=",".join(all_pairs()))
    p.add_argument("--min-sentences", type=int, default=150)
    a = p.parse_args()
    cache = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "opus")
    os.makedirs(cache, exist_ok=True)
    books = []
    for pair in [x.strip() for x in a.pairs.split(",") if x.strip()]:
        books += build_pair("-".join(sorted(pair.split("-"))), cache, a.min_sentences)
    if not books:
        sys.exit("no books were built")
    write(a.out, books)
    log(f"wrote {len(books)} books to {a.out}")


if __name__ == "__main__":
    main()
