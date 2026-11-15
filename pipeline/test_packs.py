#!/usr/bin/env python3
"""Tests for make_packs.py. Run: cd pipeline && python3 test_packs.py"""
import gzip
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class Packs(unittest.TestCase):
    def test_catalog_matches_files(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "corpora.db")
            con = sqlite3.connect(db)
            con.executescript("CREATE TABLE meta (key TEXT, value TEXT); INSERT INTO meta VALUES ('built_at','2026-11-15');"
                              "CREATE TABLE stext (group_id INT, lang TEXT, ord INT, text TEXT);"
                              "INSERT INTO stext VALUES (1,'en',0,'Hello.'),(1,'fr',0,'Bonjour.');")
            con.commit(); con.close()
            out = os.path.join(d, "dist")
            subprocess.run([sys.executable, os.path.join(HERE, "make_packs.py"), "--out", out, "--corpora", db],
                           check=True, capture_output=True)
            cat = json.load(open(os.path.join(out, "packs.json")))
            self.assertEqual(cat["format"], 1)
            p = cat["packs"][0]
            self.assertEqual((p["id"], p["kind"], p["version"]), ("corpora", "corpora", "2026-11-15"))
            self.assertEqual(p["languages"], ["en", "fr"])
            self.assertTrue(p["url"].endswith("/releases/download/data-packs/corpora.db.gz"))
            gz = os.path.join(out, "corpora.db.gz")
            self.assertEqual(p["bytes"], os.path.getsize(gz))
            self.assertEqual(p["sha256"], hashlib.sha256(open(gz, "rb").read()).hexdigest())
            self.assertEqual(gzip.open(gz).read(), open(db, "rb").read())

    def test_rejects_non_dictionary(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "x.db"); sqlite3.connect(db).close()
            r = subprocess.run([sys.executable, os.path.join(HERE, "make_packs.py"), "--out", d, "--dictionary", db], capture_output=True)
            self.assertNotEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
