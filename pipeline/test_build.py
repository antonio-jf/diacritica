#!/usr/bin/env python3
"""Tests for the pipeline. Run: python3 pipeline/test_build.py (after sample/make_sample.py)."""
import pathlib
import sqlite3
import unittest

from build_dictionary import (normalize, pinyin_numbers_to_marks, pinyin_compact_numbers, pinyin_toneless, parse_measure,
                              etymology_text, synonym_list, MAX_ETYMOLOGY)

DB = pathlib.Path(__file__).parent.parent / "app/src/main/assets/dict/dictionary.db"


class Pinyin(unittest.TestCase):
    def test_marks(self):
        self.assertEqual(pinyin_numbers_to_marks("yao4 shi5"), "yàoshi")
        self.assertEqual(pinyin_numbers_to_marks("ni3 hao3"), "nǐhǎo")
        self.assertEqual(pinyin_numbers_to_marks("Xi1 an1"), "Xī'ān")
        self.assertEqual(pinyin_numbers_to_marks("nu:3 er2"), "nǚ'ér")
        self.assertEqual(pinyin_numbers_to_marks("gou3"), "gǒu")
        self.assertEqual(pinyin_numbers_to_marks("liu2"), "liú")
        self.assertEqual(pinyin_numbers_to_marks("gui4"), "guì")
        self.assertEqual(pinyin_numbers_to_marks("lu:e4"), "lüè")

    def test_compact(self):
        self.assertEqual(pinyin_compact_numbers("Nu:3 er2"), "nu3er2")
        self.assertEqual(pinyin_toneless("yao4 shi5"), "yaoshi")

    def test_measure(self):
        self.assertEqual(parse_measure("CL:隻|只[zhi1],條|条[tiao2]"), "只 zhī · 条 tiáo")


class Norm(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(normalize("Schlüssel"), "schlussel")
        self.assertEqual(normalize("  Clé "), "cle")
        self.assertEqual(normalize("Straße"), "strasse")
        self.assertEqual(normalize("œuvre"), "oeuvre")
        self.assertEqual(normalize("tonalità"), "tonalita")


class Extras(unittest.TestCase):
    def test_etymology(self):
        self.assertIsNone(etymology_text({}))
        self.assertEqual(etymology_text({"etymology_text": "  From  Latin\n casa. "}), "From Latin casa.")
        long = "First sentence about the word. " * 40
        t = etymology_text({"etymology_text": long})
        self.assertLessEqual(len(t), MAX_ETYMOLOGY)
        self.assertTrue(t.endswith("."))

    def test_synonyms(self):
        e = {"word": "Haus", "synonyms": [{"word": "Gebäude"}, {"word": "Haus"}, {"word": "Thesaurus:Haus"}],
             "senses": [{"synonyms": [{"word": "Heim"}, {"word": "Gebäude"}]}, {}]}
        self.assertEqual(synonym_list(e), ["Gebäude", "Heim"])
        self.assertEqual(synonym_list({"word": "x"}), [])


@unittest.skipUnless(DB.exists(), "build the sample DB first")
class SampleDb(unittest.TestCase):
    def setUp(self):
        self.con = sqlite3.connect(DB)

    def q(self, sql, *a):
        return self.con.execute(sql, a).fetchall()

    def test_key_senses(self):
        rows = self.q("SELECT label, pos FROM concept WHERE en_norm='key' ORDER BY rank")
        self.assertEqual([r[0] for r in rows][:3],
                         ["device used to open a lock", "musical tonality", "button on a keyboard"])

    def test_translations_lock(self):
        rows = dict(self.q("SELECT t.lang, t.word || '|' || IFNULL(t.gender,'') || '|' || IFNULL(t.roman,'') "
                           "FROM translation t JOIN concept c ON c.id=t.concept_id "
                           "WHERE c.en_norm='key' AND c.rank=0"))
        self.assertEqual(rows["fr"], "clé|f|")
        self.assertEqual(rows["de"], "Schlüssel|m|")
        self.assertEqual(rows["zh"], "钥匙||yàoshi")  # traditional and simplified collapse into one row

    def test_reverse_lookup(self):
        rows = self.q("SELECT c.en_word FROM translation t JOIN concept c ON c.id=t.concept_id "
                      "WHERE t.lang='de' AND t.norm=?", normalize("schlussel"))
        self.assertEqual(rows, [("key",)])

    def test_entry_details(self):
        e = self.q("SELECT gender, plural, ipa FROM entry WHERE lang='de' AND word='Haus'")[0]
        self.assertEqual(e, ("n", "Häuser", "/haʊ̯s/"))
        e = self.q("SELECT gender FROM entry WHERE lang='es' AND word='mar'")[0]
        self.assertEqual(e, ("m/f",))

    def test_extras(self):
        r = self.q("SELECT x.etymology, x.synonyms FROM extra x JOIN entry e ON e.id=x.entry_id WHERE e.lang='de' AND e.word='Haus'")
        self.assertTrue(r[0][0].startswith("From Middle High German"))
        self.assertEqual(r[0][1], "Gebäude|Heim")
        forms = [f for (f,) in self.q("SELECT f.form FROM form f JOIN entry e ON e.id=f.entry_id WHERE e.word='anfangen'")]
        self.assertIn("fängt an", forms)

    def test_proper_nouns(self):
        # Countries are proper nouns ("name"); they must be kept, with their translations.
        rows = dict(self.q("SELECT t.lang, t.word FROM translation t JOIN concept c ON c.id = t.concept_id WHERE c.en_norm = 'germany'"))
        self.assertEqual(rows.get("de"), "Deutschland")
        self.assertEqual(rows.get("zh"), "德国")

    def test_form_of(self):
        r = self.q("SELECT s.form_of FROM sense s JOIN entry e ON e.id=s.entry_id WHERE e.lang='it' AND e.word='chiavi'")
        self.assertEqual(r, [("chiave",)])

    def test_zh(self):
        r = self.q("SELECT simp, trad, pinyin, measure, hsk FROM zh WHERE pinyin_norm='gou'")[0]
        self.assertEqual(r, ("狗", "狗", "gǒu", "只 zhī · 条 tiáo", 1))
        self.assertEqual(self.q("SELECT simp FROM zh WHERE pinyin_num LIKE 'yao4shi%'"), [("钥匙",)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
