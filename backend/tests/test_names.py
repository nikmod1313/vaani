"""Tests for transliteration and customer name matching."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.names import (  # noqa: E402
    normalise_name,
    resolve,
    token_set_ratio,
    transliterate,
)

KNOWN = ["Rahul Verma", "Priya Sharma", "Imran Khan", "Sunita Devi", "Mohan Lal"]


class TestTransliteration(unittest.TestCase):
    def test_common_names(self):
        self.assertEqual(transliterate("राहुल"), "rahul")
        self.assertEqual(transliterate("प्रिया"), "priya")

    def test_cluster_drops_inherent_vowel(self):
        # The virama in प्र must not produce "pariya".
        self.assertNotIn("pa", transliterate("प्रिया"))

    def test_trailing_inherent_vowel_trimmed(self):
        self.assertEqual(transliterate("रमेश"), "ramesh")

    def test_latin_passes_through(self):
        self.assertEqual(transliterate("Rahul"), "Rahul")


class TestNormalise(unittest.TestCase):
    def test_honorifics_removed(self):
        self.assertEqual(normalise_name("Rahul ji"), "rahul")
        self.assertEqual(normalise_name("Sharma bhaiya"), "sharma")

    def test_case_and_punctuation(self):
        self.assertEqual(normalise_name("RAHUL  VERMA!"), "rahul verma")

    def test_devanagari_and_latin_converge(self):
        self.assertEqual(normalise_name("राहुल"), normalise_name("Rahul"))


class TestTokenSetRatio(unittest.TestCase):
    def test_identical(self):
        self.assertEqual(token_set_ratio("rahul verma", "rahul verma"), 1.0)

    def test_order_insensitive(self):
        self.assertEqual(token_set_ratio("rahul verma", "verma rahul"), 1.0)

    def test_subset_scores_high(self):
        self.assertGreater(token_set_ratio("rahul", "rahul verma"), 0.8)

    def test_unrelated_scores_low(self):
        self.assertLess(token_set_ratio("rahul verma", "imran khan"), 0.5)


class TestResolve(unittest.TestCase):
    def test_exact_match(self):
        m = resolve("Rahul Verma", KNOWN)
        self.assertEqual(m.name, "Rahul Verma")
        self.assertFalse(m.is_new)
        self.assertTrue(m.confident)

    def test_first_name_only(self):
        m = resolve("Rahul", KNOWN)
        self.assertEqual(m.name, "Rahul Verma")
        self.assertFalse(m.is_new)

    def test_devanagari_matches_latin_record(self):
        m = resolve("राहुल", KNOWN)
        self.assertEqual(m.name, "Rahul Verma")
        self.assertFalse(m.is_new)

    def test_honorific_ignored(self):
        m = resolve("Rahul ji", KNOWN)
        self.assertEqual(m.name, "Rahul Verma")

    def test_unknown_name_is_new(self):
        m = resolve("Ganesh Iyer", KNOWN)
        self.assertTrue(m.is_new)
        self.assertEqual(m.name, "Ganesh Iyer")

    def test_never_force_a_bad_match(self):
        """Merging two customers is worse than creating a duplicate."""
        m = resolve("Vikram", KNOWN)
        self.assertTrue(m.is_new)

    def test_empty_input(self):
        self.assertIsNone(resolve("", KNOWN).name)
        self.assertIsNone(resolve("   ", KNOWN).name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
