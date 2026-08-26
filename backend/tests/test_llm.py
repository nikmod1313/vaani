"""
Tests for stage 2 wiring and the correction store.

Uses a fake LLM throughout -- no API key, no network, no cost. What is being
tested is the contract between the pipeline and the layer: which gaps get
asked about, what the rules are allowed to override, and that a model failure
degrades instead of losing the entry.
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.corrections import CorrectionStore  # noqa: E402
from app.extraction.llm import (  # noqa: E402
    ClaudeLayer,
    ExtractedFields,
    _build_user_prompt,
)
from app.extraction.pipeline import extract  # noqa: E402
from app.extraction.rules import apply_rules  # noqa: E402

KNOWN = ["Rahul Verma", "Priya Sharma", "Imran Khan"]


class FakeLLM:
    """Records what it was asked, returns what it was told to."""

    def __init__(self, response: dict | None = None, fail: bool = False):
        self.response = response or {}
        self.fail = fail
        self.calls: list[dict] = []

    def extract(self, text, gaps, known_customers, rule_result):
        self.calls.append({
            "text": text,
            "gaps": gaps,
            "known_customers": known_customers,
        })
        if self.fail:
            raise RuntimeError("model unavailable")
        return self.response


class TestGapTargeting(unittest.TestCase):
    """The model should only ever be asked about what rules could not settle."""

    def test_not_called_when_rules_are_complete(self):
        llm = FakeLLM()
        text = "2 kilo chawal aur 1 packet chai, dhai sau ka udhaar"
        extract(text, KNOWN, sender_name="Rahul Verma", llm=llm)
        self.assertEqual(llm.calls, [], "rules resolved everything; no call needed")

    def test_called_with_only_the_missing_fields(self):
        llm = FakeLLM()
        # No payment word and no currency marker -> amount and mode unresolved.
        extract("chawal 300", KNOWN, sender_name="Rahul Verma", llm=llm)
        self.assertEqual(len(llm.calls), 1)
        gaps = llm.calls[0]["gaps"]
        self.assertIn("amount", gaps)
        self.assertIn("mode", gaps)
        self.assertNotIn("items", gaps)

    def test_shop_vocabulary_is_passed_through(self):
        llm = FakeLLM()
        extract("kuch bhej do", KNOWN, sender_name="Rahul Verma", llm=llm)
        self.assertEqual(llm.calls[0]["known_customers"], KNOWN)


class TestRulesWin(unittest.TestCase):
    """Stage 2 fills gaps. It does not overrule arithmetic that succeeded."""

    def test_resolved_amount_is_not_replaced(self):
        llm = FakeLLM({"amount": 999})
        result = extract(
            "2 kilo chawal, dhai sau ka udhaar", KNOWN,
            sender_name="Rahul Verma", llm=llm,
        )
        self.assertEqual(result.amount, 250)

    def test_model_fills_an_unresolved_mode(self):
        llm = FakeLLM({"mode": "cash"})
        result = extract("chawal 300", KNOWN, sender_name="Rahul Verma", llm=llm)
        self.assertEqual(result.mode, "cash")

    def test_source_records_that_the_model_ran(self):
        llm = FakeLLM({"mode": "cash"})
        result = extract("chawal 300", KNOWN, sender_name="Rahul Verma", llm=llm)
        self.assertEqual(result.source, "rules+llm")

        no_llm = extract("chawal 300", KNOWN, sender_name="Rahul Verma")
        self.assertEqual(no_llm.source, "rules")


class TestAgreementAffectsConfidence(unittest.TestCase):
    """Agreement between the two layers is a real signal, in both directions."""

    def test_agreement_raises_confidence(self):
        text = "chawal 300"
        base = extract(text, KNOWN, sender_name="Rahul Verma")
        agree = extract(
            text, KNOWN, sender_name="Rahul Verma", llm=FakeLLM({"amount": 300}),
        )
        self.assertGreater(
            agree.confidence["fields"]["amount"]["score"],
            base.confidence["fields"]["amount"]["score"],
        )

    def test_disagreement_lowers_confidence_sharply(self):
        text = "chawal 300"
        base = extract(text, KNOWN, sender_name="Rahul Verma")
        disagree = extract(
            text, KNOWN, sender_name="Rahul Verma", llm=FakeLLM({"amount": 500}),
        )
        self.assertLess(
            disagree.confidence["fields"]["amount"]["score"],
            base.confidence["fields"]["amount"]["score"],
        )
        self.assertIn("disagree", disagree.confidence["fields"]["amount"]["reason"])


class TestDegradation(unittest.TestCase):
    """A model failure must never cost the shopkeeper an entry."""

    def test_model_exception_falls_back_to_rules(self):
        result = extract(
            "2 kilo chawal, dhai sau ka udhaar", KNOWN,
            sender_name="Rahul Verma", llm=FakeLLM(fail=True),
        )
        self.assertEqual(result.amount, 250)
        self.assertEqual(result.mode, "udhaar")
        self.assertEqual(result.source, "rules")

    def test_layer_without_credentials_reports_unavailable(self):
        layer = ClaudeLayer(client=None)
        # No key in the test environment -> unavailable, and extract is a no-op.
        if not layer.available():
            self.assertEqual(layer.extract("x", ["amount"], [], apply_rules("x")), {})

    def test_constructed_client_without_credentials_is_not_available(self):
        """
        Regression: `Anthropic()` constructs fine with no credentials and only
        fails at request time. Treating construction as proof of availability
        made the eval print "rules + claude-opus-5" over numbers the model had
        never touched.
        """
        class KeylessClient:
            api_key = None
            auth_token = None

        self.assertFalse(ClaudeLayer(client=KeylessClient()).available())

        class KeyedClient:
            api_key = "sk-test"
            auth_token = None

        self.assertTrue(ClaudeLayer(client=KeyedClient()).available())

    def test_failed_calls_are_counted(self):
        """A silent degradation has to be observable after the fact."""
        class ExplodingClient:
            api_key = "sk-test"
            auth_token = None

            class messages:
                @staticmethod
                def parse(**kwargs):
                    raise RuntimeError("network down")

        layer = ClaudeLayer(client=ExplodingClient())
        with self.assertRaises(RuntimeError):
            layer.extract("chawal 300", ["mode"], [], apply_rules("chawal 300"))

        self.assertEqual(layer.calls_failed, 1)
        self.assertEqual(layer.calls_ok, 0)
        self.assertIn("network down", layer.last_error)


class TestPromptConstruction(unittest.TestCase):
    def test_prompt_states_what_rules_resolved(self):
        rules = apply_rules("2 kilo chawal, dhai sau ka udhaar")
        prompt = _build_user_prompt("2 kilo chawal, dhai sau ka udhaar", [], KNOWN, rules)
        self.assertIn("do not change", prompt)
        self.assertIn("250", prompt)

    def test_prompt_lists_only_requested_gaps(self):
        rules = apply_rules("chawal 300")
        prompt = _build_user_prompt("chawal 300", ["mode"], KNOWN, rules)
        self.assertIn("FILL ONLY THESE GAPS", prompt)
        self.assertIn("mode", prompt)

    def test_prompt_carries_customer_list(self):
        rules = apply_rules("chawal 300")
        prompt = _build_user_prompt("chawal 300", ["mode"], KNOWN, rules)
        self.assertIn("Rahul Verma", prompt)


class TestSchema(unittest.TestCase):
    def test_defaults_are_safe(self):
        f = ExtractedFields()
        self.assertIsNone(f.amount)
        self.assertEqual(f.mode, "unknown")
        self.assertEqual(f.items, [])

    def test_gap_dict_always_includes_amount_for_agreement_check(self):
        parsed = ExtractedFields(amount=250, mode="cash")
        # amount is not in gaps, but must still come back so the pipeline can
        # compare the two layers.
        result = ClaudeLayer._to_gap_dict(parsed, gaps=["mode"])
        self.assertEqual(result["amount"], 250)
        self.assertEqual(result["mode"], "cash")

    def test_gap_dict_omits_fields_not_asked_about(self):
        parsed = ExtractedFields(items=["chawal"], mode="cash")
        result = ClaudeLayer._to_gap_dict(parsed, gaps=["mode"])
        self.assertNotIn("items", result)


class TestCorrectionStore(unittest.TestCase):
    def setUp(self):
        self.store = CorrectionStore()
        self.store.add(
            "2 kilo chawal bhej do",
            corrected={"amount": 120, "mode": "udhaar", "items": ["chawal 2kg"]},
            predicted={"amount": None, "mode": "unknown", "items": ["chawal 2kg"]},
        )
        self.store.add(
            "1 packet chai bhej do",
            corrected={"amount": 60, "mode": "udhaar", "items": ["chai 1packet"]},
            predicted={"amount": 60, "mode": "cash", "items": ["chai 1packet"]},
        )

    def test_similar_finds_the_close_one(self):
        hits = self.store.similar("3 kilo chawal bhej do")
        self.assertTrue(hits)
        self.assertIn("chawal", hits[0].raw_text)

    def test_weak_matches_are_excluded(self):
        # An irrelevant example is worse than none.
        self.assertEqual(self.store.similar("payment mila 500 upi"), [])

    def test_changed_fields_identifies_the_real_fix(self):
        rows = self.store.all()
        self.assertIn("amount", rows[0].changed_fields())
        self.assertNotIn("items", rows[0].changed_fields())
        self.assertIn("mode", rows[1].changed_fields())

    def test_field_error_counts_ranks_weaknesses(self):
        # Row 1 needed both amount and mode fixed; row 2 only mode. An
        # unknown -> udhaar fix counts: the engine failed to decide, and the
        # human had to. That is exactly the weakness signal we want.
        counts = self.store.field_error_counts()
        self.assertEqual(counts.get("mode"), 2)
        self.assertEqual(counts.get("amount"), 1)
        # Ranked worst-first, so the report reads off the top.
        self.assertEqual(next(iter(counts)), "mode")

    def test_as_json_emits_only_ledger_fields(self):
        row = self.store.all()[0]
        self.assertNotIn("created_at", row.as_json())
        self.assertIn("amount", row.as_json())

    def test_persists_across_instances(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "corrections.jsonl"
            first = CorrectionStore(path)
            first.add("chawal 2 kilo", {"amount": 120})
            self.assertEqual(len(CorrectionStore(path)), 1)

    def test_shops_are_isolated(self):
        self.store.add("chawal", {"amount": 999}, shop_id="other")
        self.assertEqual(len(self.store.all("other")), 1)
        self.assertEqual(self.store.similar("chawal", shop_id="default")[0].corrected["amount"], 120)


class TestFewShotWiring(unittest.TestCase):
    def test_layer_builds_few_shot_turns_from_corrections(self):
        store = CorrectionStore()
        store.add("2 kilo chawal bhej do", {"amount": 120, "mode": "udhaar"})

        layer = ClaudeLayer(client=object(), corrections=store)
        messages = layer._few_shot_messages("3 kilo chawal bhej do")

        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")
        self.assertEqual(messages[1]["role"], "assistant")
        self.assertIn("120", messages[1]["content"])

    def test_no_corrections_means_no_few_shots(self):
        layer = ClaudeLayer(client=object(), corrections=CorrectionStore())
        self.assertEqual(layer._few_shot_messages("anything"), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
