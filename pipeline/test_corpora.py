#!/usr/bin/env python3
"""Offline tests for build_corpora.py, using small fake OPUS files. Run: cd pipeline && python3 test_corpora.py"""
import argparse
import os
import tempfile
import unittest
import zipfile

from build_corpora import CORPORA, build_corpus, good_english, good_translation, level_for, pair

RANKS = {w: i + 1 for i, w in enumerate(
    "the we must protect our rivers and forests city council approved a new plan for parks "
    "this is important people in many countries read news every day".split())}


def fake_zip(folder, corpus, lang, pairs):
    path = os.path.join(folder, f"{corpus}.{pair(lang)}.zip")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(f"{corpus}.{pair(lang)}.en", "\n".join(e for e, _ in pairs) + "\n")
        z.writestr(f"{corpus}.{pair(lang)}.{lang}", "\n".join(x for _, x in pairs) + "\n")
        z.writestr("README", "fake")


class Filters(unittest.TestCase):
    def test_english(self):
        self.assertTrue(good_english("We must protect our rivers and forests.", 6, 22))
        self.assertFalse(good_english("Mr President, we must protect our rivers.", 6, 22))      # procedure
        self.assertFalse(good_english("(Applause)", 1, 22))
        self.assertFalse(good_english("See https://example.org for the full plan today.", 6, 22))
        self.assertFalse(good_english("We approved 2,500,000 euros for parks.", 6, 22))           # numbers
        self.assertFalse(good_english("Too short.", 6, 22))

    def test_translation(self):
        self.assertTrue(good_translation("Nous devons protéger nos rivières et nos forêts.", "fr", "We must protect our rivers and forests."))
        self.assertFalse(good_translation("We must protect our rivers and forests.", "fr", "We must protect our rivers and forests."))
        self.assertTrue(good_translation("我们必须保护我们的河流和森林。", "zh", "We must protect our rivers and forests."))
        self.assertFalse(good_translation("We must", "zh", "We must protect our rivers and forests."))

    def test_level(self):
        self.assertEqual(level_for("We must protect our rivers and forests.", RANKS), "A2")
        self.assertIsNone(level_for("We must safeguard our rivers.", RANKS))
        self.assertEqual(level_for("People in Paris read news every day.", RANKS), "A2")  # names skipped

    def test_pairs_are_alphabetical(self):
        self.assertEqual(pair("de"), "de-en")
        self.assertEqual(pair("fr"), "en-fr")


class EndToEnd(unittest.TestCase):
    def test_groups_join_on_english(self):
        corpus = CORPORA["news"][0]
        a = "We must protect our rivers and forests."
        b = "The city council approved a new plan for parks."
        with tempfile.TemporaryDirectory() as d:
            fake_zip(d, corpus, "fr", [(a, "Nous devons protéger nos rivières et nos forêts."), (b, "Le conseil a approuvé un plan pour les parcs.")])
            fake_zip(d, corpus, "de", [(a, "Wir müssen unsere Flüsse und Wälder schützen.")])
            fake_zip(d, corpus, "es", [(b, "El ayuntamiento aprobó un plan nuevo para los parques.")])
            fake_zip(d, corpus, "it", [])
            fake_zip(d, corpus, "zh", [(a, "我们必须保护我们的河流和森林。")])
            args = argparse.Namespace(sample=1.0, max_candidates=1000, min_words=6, max_words=22, min_langs=2,
                                      per_corpus=100, seed=1)
            out = {en: tr for en, _lvl, tr in build_corpus("news", args, RANKS, d)}
        self.assertEqual(set(out[a]), {"fr", "de", "zh"})
        self.assertEqual(set(out[b]), {"fr", "es"})


if __name__ == "__main__":
    unittest.main()
