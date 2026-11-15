#!/usr/bin/env python3
"""
Package Diacritica's data as downloadable packs and write the catalog (packs.json) the app reads.

Each database is gzipped and listed with its size, SHA-256 and version. Upload everything in the
output folder to the GitHub release with the tag "data-packs"; the app reads
  https://github.com/<owner>/<repo>/releases/download/data-packs/packs.json

  cd pipeline
  python3 make_packs.py --out dist \\
      --dictionary ../app/src/main/assets/dict/dictionary.db \\
      --sentences ../app/src/main/assets/sentences/sentences.db \\
      --corpora ../app/src/main/assets/corpora/corpora.db
  gh release create data-packs dist/* --title "Data packs" --notes "Offline data for Diacritica"
  # later updates: gh release upload data-packs dist/* --clobber

Only the databases you pass are packed. Versions default to each file's built_at date.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import sqlite3
import sys

PACKS = {
    "dictionary": dict(
        kind="dictionary", title="Full dictionary",
        description="All six languages from Wiktionary and CC-CEDICT: translations, grammar, conjugation and declension tables, examples.",
        license="CC BY-SA 4.0 (Wiktionary, CC-CEDICT)", file="dictionary.db.gz",
    ),
    "sentences": dict(
        kind="sentences", title="Everyday sentences",
        description="Tatoeba sentences with human translations, for Rehearse › Everyday, My words and Reverse.",
        license="CC BY 2.0 FR (Tatoeba)", file="sentences.db.gz",
    ),
    "corpora": dict(
        kind="corpora", title="News, Formal and Encyclopedic sentences",
        description="Human-translated sentences from Global Voices, Europarl and translated Wikipedia articles (OPUS).",
        license="CC BY 3.0 (Global Voices), Europarl, CC BY-SA (Wikimedia)", file="corpora.db.gz",
    ),
}


def meta(db: str) -> dict[str, str]:
    try:
        with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as con:
            return dict(con.execute("SELECT key, value FROM meta").fetchall())
    except sqlite3.Error:
        return {}


def languages(pack_id: str, db: str, m: dict[str, str]) -> list[str]:
    if m.get("languages"):
        return [l for l in m["languages"].split(",") if l]
    try:
        with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as con:
            return sorted(r[0] for r in con.execute("SELECT DISTINCT lang FROM stext"))
    except sqlite3.Error:
        return []


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pack(pack_id: str, db: str, out: str, base_url: str, version: str | None) -> dict:
    info = PACKS[pack_id]
    m = meta(db)
    if pack_id == "dictionary" and "schema_version" not in m:
        sys.exit(f"{db} doesn't look like a Diacritica dictionary (no schema_version in meta)")
    target = os.path.join(out, info["file"])
    with open(db, "rb") as src, gzip.open(target, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst, 1 << 20)
    entry = {
        "id": pack_id,
        "kind": info["kind"],
        "title": info["title"],
        "description": info["description"],
        "version": version or m.get("built_at") or "1",
        "url": base_url.rstrip("/") + "/" + info["file"],
        "bytes": os.path.getsize(target),
        "installedBytes": os.path.getsize(db),
        "sha256": sha256(target),
        "languages": languages(pack_id, db, m),
        "license": info["license"],
    }
    print(f"{pack_id}: {entry['installedBytes'] / 1e6:.1f} MB → {entry['bytes'] / 1e6:.1f} MB ({info['file']})", file=sys.stderr)
    return entry


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True)
    p.add_argument("--repo", default="antonio-jf/diacritica")
    p.add_argument("--tag", default="data-packs")
    p.add_argument("--version", help="override the version for every pack")
    for k in PACKS:
        p.add_argument(f"--{k}", help=f"path to the {k} database")
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    base = f"https://github.com/{a.repo}/releases/download/{a.tag}"
    entries = [pack(k, getattr(a, k), a.out, base, a.version) for k in PACKS if getattr(a, k)]
    if not entries:
        p.error("pass at least one of --dictionary, --sentences, --corpora")
    with open(os.path.join(a.out, "packs.json"), "w", encoding="utf-8") as fh:
        json.dump({"format": 1, "packs": entries}, fh, ensure_ascii=False, indent=1)
    print(f"wrote {len(entries)} packs and packs.json to {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
