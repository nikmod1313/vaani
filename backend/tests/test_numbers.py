"""Tests for the Hindi/Hinglish number parser."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.numbers import (  # noqa: E402
    find_numbers,
    parse_number,
    rupees_to_words,
    to_words,
)


class TestBasicNumbers(unittest.TestCase):
    def test_units(self):
        for text, expected in [
            ("ek", 1), ("teen", 3), ("chaar", 4), ("paanch", 5),
            ("das", 10), ("bees", 20), ("pachas", 50), ("sau", 100),
        ]:
            self.assertEqual(parse_number(text), expected, text)

    def test_spelling_variants(self):
        # Shopkeepers type these however they like.
        self.assertEqual(parse_number("char"), 4)
        self.assertEqual(parse_number("panch"), 5)
        self.assertEqual(parse_number("tin"), 3)
        self.assertEqual(parse_number("hajar"), 1000)
        self.assertEqual(parse_number("hazar"), 1000)

    def test_devanagari(self):
        # "दो" is excluded here for the same reason as Latin "do" -- bare and
        # trailing, it is the verb. Covered in TestDoAmbiguity instead.
        self.assertEqual(parse_number("पाँच"), 5)
        self.assertEqual(parse_number("सौ"), 100)
        self.assertEqual(parse_number("तीन"), 3)

    def test_devanagari_digits(self):
        self.assertEqual(parse_number("२५०"), 250)


class TestDoAmbiguity(unittest.TestCase):
    """
    "do" means 2, but far more often it is the imperative "give/send".
    Getting this wrong turns "chai bhej do, dhai sau" into 450 instead of 250,
    which is real money in someone's khata.
    """

    def test_do_before_a_unit_is_two(self):
        self.assertEqual(parse_number("do kilo chawal"), 2)

    def test_do_before_a_multiplier_is_two(self):
        self.assertEqual(parse_number("do hazaar"), 2000)

    def test_do_after_a_verb_stem_is_not_a_number(self):
        self.assertIsNone(parse_number("chai bhej do"))
        self.assertIsNone(parse_number("likh do"))
        self.assertIsNone(parse_number("kar do"))

    def test_do_at_clause_end_is_not_a_number(self):
        self.assertIsNone(parse_number("chawal bhej do"))

    def test_clause_boundary_stops_the_run(self):
        # The exact failure this rule exists for.
        text = "chai bhej do, dhai sau ka udhaar"
        self.assertEqual([v for v, _ in find_numbers(text)], [250])

    def test_boundary_without_comma(self):
        # Verb-stem rule has to carry it when punctuation is missing.
        text = "chai bhej do dhai sau ka udhaar"
        self.assertIn(250, [v for v, _ in find_numbers(text)])

    def test_devanagari_do_is_also_a_verb(self):
        # "नमक और मिर्च भेज दो" is "send salt and chilli", not "…send 2".
        self.assertIsNone(parse_number("नमक और मिर्च भेज दो"))
        # With a unit after it, it is a real quantity again.
        self.assertEqual(parse_number("दो किलो चावल"), 2)


class TestFractions(unittest.TestCase):
    """The cases the whole feature is judged on."""

    def test_dhai_sau_is_250(self):
        self.assertEqual(parse_number("dhai sau"), 250)

    def test_dedh_sau_is_150(self):
        self.assertEqual(parse_number("dedh sau"), 150)

    def test_sava_sau_is_125(self):
        # Implied 1 before the multiplier.
        self.assertEqual(parse_number("sava sau"), 125)

    def test_paune_sau_is_75(self):
        self.assertEqual(parse_number("paune sau"), 75)

    def test_paune_do_sau_is_175(self):
        self.assertEqual(parse_number("paune do sau"), 175)

    def test_sava_do_sau_is_225(self):
        self.assertEqual(parse_number("sava do sau"), 225)

    def test_sadhe_teen_sau_is_350(self):
        self.assertEqual(parse_number("sadhe teen sau"), 350)

    def test_dhai_hazaar_is_2500(self):
        self.assertEqual(parse_number("dhai hazaar"), 2500)

    def test_standalone_fractions(self):
        self.assertEqual(parse_number("adha"), 0.5)
        self.assertEqual(parse_number("pav"), 0.25)
        self.assertEqual(parse_number("dhai"), 2.5)

    def test_devanagari_fractions(self):
        self.assertEqual(parse_number("ढाई सौ"), 250)
        self.assertEqual(parse_number("डेढ़ सौ"), 150)


class TestCompounds(unittest.TestCase):
    def test_thousands_plus_hundreds(self):
        self.assertEqual(parse_number("do hazaar paanch sau"), 2500)

    def test_thousands_hundreds_tens(self):
        self.assertEqual(parse_number("chaar hazaar do sau chalees"), 4240)

    def test_lakh(self):
        self.assertEqual(parse_number("ek lakh"), 100_000)
        self.assertEqual(parse_number("dedh lakh"), 150_000)

    def test_filler_between_groups(self):
        self.assertEqual(parse_number("do hazaar aur paanch sau"), 2500)


class TestInSentences(unittest.TestCase):
    def test_the_canonical_order(self):
        text = "Bhaiya 2 kilo chawal aur 1 packet chai bhej do, dhai sau ka udhaar likh lena"
        values = [v for v, _ in find_numbers(text)]
        self.assertIn(250, values)

    def test_multiple_numbers_in_order(self):
        values = [v for v, _ in find_numbers("2 kilo chawal aur teen packet chai")]
        self.assertEqual(values[:2], [2, 3])

    def test_digits_alongside_words(self):
        self.assertEqual(parse_number("250 rupaye"), 250)

    def test_no_number_returns_none(self):
        self.assertIsNone(parse_number("namaste bhaiya kaise ho"))

    def test_trailing_filler_not_swallowed(self):
        # "aur" at the end should not extend the run past the number.
        values = [v for v, _ in find_numbers("do kilo aur")]
        self.assertEqual(values, [2])


class TestToWords(unittest.TestCase):
    """The reverse direction, used by the spoken dashboard."""

    def test_simple(self):
        self.assertEqual(to_words(0), "shunya")
        self.assertEqual(to_words(5), "paanch")
        self.assertEqual(to_words(100), "ek sau")

    def test_the_dashboard_number(self):
        self.assertEqual(to_words(4240), "chaar hazaar do sau chalees")

    def test_indian_grouping(self):
        self.assertEqual(to_words(100_000), "ek lakh")
        self.assertTrue(to_words(250_000).startswith("do lakh"))

    def test_rupees(self):
        self.assertEqual(rupees_to_words(250), "do sau pachas rupaye")

    def test_round_trip(self):
        """Anything we can say, we must be able to read back."""
        for n in [1, 7, 19, 42, 75, 125, 150, 250, 840, 1090, 2500, 4240]:
            with self.subTest(n=n):
                self.assertEqual(parse_number(to_words(n)), n)


if __name__ == "__main__":
    unittest.main(verbosity=2)
