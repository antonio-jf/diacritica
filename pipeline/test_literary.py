#!/usr/bin/env python3
"""Offline tests for build_literary.py (no downloads). Run: cd pipeline && python3 test_literary.py"""
import unittest

from build_literary import clean, level_for, paragraphs, split_sentences, strip_gutenberg


class Split(unittest.TestCase):
    def test_abbreviations_and_dialogue(self):
        p = "M. Dupont entra dans la maison. Il avait faim. « Tu viens ? » demanda-t-elle."
        self.assertEqual(split_sentences(p, "fr"), [
            "M. Dupont entra dans la maison.", "Il avait faim.", "« Tu viens ? » demanda-t-elle.",
        ])

    def test_english_titles(self):
        self.assertEqual(split_sentences("Mr. Darcy smiled. She left.", "en"), ["Mr. Darcy smiled.", "She left."])


class Clean(unittest.TestCase):
    def test_keeps_good_sentences(self):
        self.assertEqual(clean("— Je ne sais pas, répondit l'homme."), "Je ne sais pas, répondit l'homme.")
        self.assertEqual(clean("\"We should go home.\""), "We should go home.")

    def test_drops_noise(self):
        self.assertIsNone(clean("Elle avait 23 ans."))               # numbers
        self.assertIsNone(clean("Il dit: oui."))                      # colon
        self.assertIsNone(clean("and then he left."))                 # lowercase start
        self.assertIsNone(clean("He waited..."))                      # ellipsis
        self.assertIsNone(clean("She said (quietly."))                # unbalanced
        self.assertIsNone(clean("No end punctuation"))


class Levels(unittest.TestCase):
    ranks = {w: i + 1 for i, w in enumerate("the cat sleeps on mat and a dog l homme regarde la mer".split())}

    def test_level_by_rarest_word(self):
        self.assertEqual(level_for("The cat sleeps on the mat.", "en", self.ranks)[0], "B1")
        self.assertIsNone(level_for("The cat contemplates the mat.", "en", self.ranks)[0])

    def test_names_and_elision(self):
        self.assertEqual(level_for("The cat sleeps on Matilda.", "en", self.ranks)[0], "B1")  # names skipped
        self.assertEqual(level_for("L'homme regarde la mer.", "fr", self.ranks)[0], "B1")

    def test_german_nouns_count(self):
        self.assertIsNone(level_for("Der Hund schläft.", "de", {"der": 1, "schläft": 2})[0])


class Gutenberg(unittest.TestCase):
    def test_header_footer_and_headings(self):
        text = ("Title: X\n\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\n\nCHAPTER I\n\n"
                "It was a cold morning and the old\nfisherman looked at the sea.\n\n"
                "*** END OF THE PROJECT GUTENBERG EBOOK X ***\nLicense text")
        self.assertEqual(paragraphs(strip_gutenberg(text)), ["It was a cold morning and the old fisherman looked at the sea."])


if __name__ == "__main__":
    unittest.main()
