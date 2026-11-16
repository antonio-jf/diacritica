#!/usr/bin/env python3
"""Offline tests for build_books.py with a small fake OPUS Books zip. Run: cd pipeline && python3 test_books.py"""
import os
import sqlite3
import tempfile
import unittest
import zipfile

import build_corpora
from build_books import build_pair, title_author, write

build_corpora.OFFLINE = True

XML = """<?xml version="1.0" encoding="utf-8"?>
<cesAlign version="1.0">
<linkGrp targType="s" fromDoc="en/Austen_Jane-Pride_and_Prejudice.xml.gz" toDoc="fr/Austen_Jane-Pride_and_Prejudice.xml.gz">
<link xtargets="s1;s1" id="SL1"/>
<link xtargets="s2;" id="SL2"/>
<link xtargets="s3 s4;s2" id="SL3"/>
</linkGrp>
<linkGrp targType="s" fromDoc="en/Verne_Jules-Around_the_World.xml.gz" toDoc="fr/Verne_Jules-Around_the_World.xml.gz">
<link xtargets="s1;s1" id="SL1"/>
<link xtargets="s2;s2" id="SL2"/>
<link xtargets="s3;s3" id="SL3"/>
</linkGrp>
</cesAlign>"""
EN = ["It is a truth universally acknowledged.", "She smiled. He bowed.", "Phileas Fogg lived in London.", "He was a calm man.", "He left at once."]
FR = ["C'est une vérité universellement reconnue.", "Elle sourit et il s'inclina.", "Phileas Fogg vivait à Londres.", "C'était un homme calme.", "Il partit aussitôt."]


def fake(d, en=EN, fr=FR):
    with zipfile.ZipFile(os.path.join(d, "Books.en-fr.zip"), "w") as z:
        z.writestr("Books.en-fr.en", "\n".join(en) + "\n")
        z.writestr("Books.en-fr.fr", "\n".join(fr) + "\n")
        z.writestr("Books.en-fr.xml", XML)


class Books(unittest.TestCase):
    def test_title_author(self):
        self.assertEqual(title_author("en/Austen_Jane-Pride_and_Prejudice.xml.gz"), ("Pride and Prejudice", "Jane Austen"))
        self.assertEqual(title_author("de/Kafka_Franz-Die_Verwandlung.xml.gz"), ("Die Verwandlung", "Franz Kafka"))
        self.assertEqual(title_author("fr/Alain_Fournier-Le_grand_Meaulnes.xml.gz"), ("Le grand Meaulnes", "Alain-Fournier"))

    def test_split_into_books(self):
        with tempfile.TemporaryDirectory() as d:
            fake(d)
            books = build_pair("en-fr", d, min_sentences=1)
            self.assertEqual([b["title"] for b in books], ["Pride and Prejudice", "Around the World"])
            # The one-sided link (s2;) has no Moses line, so Austen has 2 lines and Verne 3.
            self.assertEqual(books[0]["sentences"][1], (EN[1], FR[1]))
            self.assertEqual(books[1]["sentences"][0], (EN[2], FR[2]))
            out = os.path.join(d, "books.db")
            write(out, books)
            con = sqlite3.connect(out)
            self.assertEqual(con.execute("SELECT title, author, sentences, lang_a, lang_b FROM book ORDER BY author").fetchall(),
                             [("Pride and Prejudice", "Jane Austen", 2, "en", "fr"), ("Around the World", "Jules Verne", 3, "en", "fr")])
            self.assertEqual(con.execute("SELECT a, b FROM bsent WHERE book_id = 2 AND idx = 2").fetchone(), (EN[4], FR[4]))

    def test_ids_file(self):
        # The real OPUS Books zips have a .ids file: the documents of each line.
        ids = ["en/Austen_Jane-Pride_and_Prejudice.xml.gz\tfr/Austen_Jane-Pride_and_Prejudice.xml.gz\ts1\ts1"] * 2 + \
              ["en/Verne_Jules-Around_the_World.xml.gz\tfr/Verne_Jules-Around_the_World.xml.gz\ts1\ts1"] * 3
        with tempfile.TemporaryDirectory() as d:
            with zipfile.ZipFile(os.path.join(d, "Books.en-fr.zip"), "w") as z:
                z.writestr("Books.en-fr.en", "\n".join(EN) + "\n")
                z.writestr("Books.en-fr.fr", "\n".join(FR) + "\n")
                z.writestr("Books.en-fr.ids", "\n".join(ids) + "\n")
            books = build_pair("en-fr", d, min_sentences=1)
        self.assertEqual([(b["title"], b["author"], len(b["sentences"])) for b in books],
                         [("Pride and Prejudice", "Jane Austen", 2), ("Around the World", "Jules Verne", 3)])
        self.assertEqual(books[1]["sentences"][0], (EN[2], FR[2]))

    def test_mismatch_is_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            fake(d, en=EN[:-1], fr=FR[:-1])
            self.assertEqual(build_pair("en-fr", d, min_sentences=1), [])


if __name__ == "__main__":
    unittest.main()
