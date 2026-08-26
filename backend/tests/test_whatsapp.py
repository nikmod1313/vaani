"""Tests for export parsing, the watermark, segmentation and the classifier."""

import sys
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.whatsapp import (  # noqa: E402
    ChatCursor,
    classify,
    ingest,
    parse_export,
    segment,
)

BRACKETED = """‎[26/08/26, 9:38:02 AM] Rahul Verma: Namaste bhaiya
[26/08/26, 9:39:11 AM] Sharma Kirana: Haan boliye
[26/08/26, 9:41:23 AM] Rahul Verma: Bhaiya 2 kilo chawal bhej do
[26/08/26, 9:41:44 AM] Rahul Verma: aur 1 packet chai
[26/08/26, 9:42:02 AM] Rahul Verma: dhai sau ka udhaar likh lena
[26/08/26, 9:45:00 AM] Sharma Kirana: theek hai
[26/08/26, 10:15:00 AM] Priya Sharma: 1 litre tel aur namak, 120 rupaye cash
"""

DASHED = """26/08/26, 9:41 am - Rahul Verma: 2 kilo chawal bhej do
26/08/26, 9:42 am - Sharma Kirana: ok
"""

WITH_SYSTEM = """[26/08/26, 9:00:00 AM] Rahul Verma: ‎Messages and calls are end-to-end encrypted.
[26/08/26, 9:38:02 AM] Rahul Verma: Namaste
[26/08/26, 9:39:00 AM] Rahul Verma: <Media omitted>
[26/08/26, 9:40:00 AM] Rahul Verma: 2 kilo chawal
"""

MULTILINE = """[26/08/26, 9:41:23 AM] Rahul Verma: Bhaiya list bhej raha hoon
chawal 2 kilo
chai 1 packet
[26/08/26, 9:45:00 AM] Sharma Kirana: mil gaya
"""


class TestParsing(unittest.TestCase):
    def test_bracketed_format(self):
        msgs = parse_export(BRACKETED)
        self.assertEqual(len(msgs), 7)
        self.assertEqual(msgs[0].sender, "Rahul Verma")
        self.assertEqual(msgs[0].body, "Namaste bhaiya")

    def test_dashed_format(self):
        msgs = parse_export(DASHED)
        self.assertEqual(len(msgs), 2)
        self.assertEqual(msgs[0].sender, "Rahul Verma")

    def test_timestamps_parsed(self):
        msgs = parse_export(BRACKETED)
        self.assertEqual(msgs[0].timestamp, datetime(2026, 8, 26, 9, 38, 2))

    def test_system_lines_skipped(self):
        msgs = parse_export(WITH_SYSTEM)
        bodies = [m.body for m in msgs]
        self.assertEqual(bodies, ["Namaste", "2 kilo chawal"])

    def test_multiline_messages_joined(self):
        msgs = parse_export(MULTILINE)
        self.assertEqual(len(msgs), 2)
        self.assertIn("chawal 2 kilo", msgs[0].body)
        self.assertIn("chai 1 packet", msgs[0].body)

    def test_leading_ltr_mark_stripped(self):
        msgs = parse_export(BRACKETED)
        self.assertFalse(msgs[0].raw.startswith("‎"))


class TestContentHash(unittest.TestCase):
    def test_identical_messages_hash_the_same(self):
        a = parse_export(BRACKETED)[0]
        b = parse_export(BRACKETED)[0]
        self.assertEqual(a.content_hash, b.content_hash)

    def test_whitespace_does_not_change_identity(self):
        a = parse_export("[26/08/26, 9:38:02 AM] Rahul: 2 kilo  chawal")[0]
        b = parse_export("[26/08/26, 9:38:02 AM] Rahul: 2 kilo chawal")[0]
        self.assertEqual(a.content_hash, b.content_hash)

    def test_different_senders_differ(self):
        a = parse_export("[26/08/26, 9:38:02 AM] Rahul: hi")[0]
        b = parse_export("[26/08/26, 9:38:02 AM] Priya: hi")[0]
        self.assertNotEqual(a.content_hash, b.content_hash)


class TestWatermark(unittest.TestCase):
    """Re-importing an overlapping export must not double-count."""

    def test_first_run_sees_everything(self):
        cursor = ChatCursor(chat_id="rahul")
        result = ingest(BRACKETED, cursor)
        self.assertEqual(result.new_messages, 7)

    def test_second_run_sees_nothing(self):
        cursor = ChatCursor(chat_id="rahul")
        first = ingest(BRACKETED, cursor)
        cursor.advance([m for b in first.candidates for m in b.messages])
        # Advance over everything, mimicking a fully approved review queue.
        cursor.advance(parse_export(BRACKETED))

        second = ingest(BRACKETED, cursor)
        self.assertEqual(second.new_messages, 0)
        self.assertEqual(second.candidates, [])

    def test_overlapping_export_only_yields_new(self):
        cursor = ChatCursor(chat_id="rahul")
        cursor.advance(parse_export(BRACKETED))

        extended = BRACKETED + "[26/08/26, 11:00:00 AM] Imran Khan: 3 packet biscuit, 60 rupaye\n"
        result = ingest(extended, cursor)
        self.assertEqual(result.new_messages, 1)
        self.assertEqual(len(result.candidates), 1)
        self.assertIn("biscuit", result.candidates[0].text)


class TestSegmentation(unittest.TestCase):
    def test_consecutive_same_sender_merges(self):
        blocks = segment(parse_export(BRACKETED))
        rahul_order = [b for b in blocks if "chawal" in b.text]
        self.assertEqual(len(rahul_order), 1)
        # The three-part order must arrive as one block.
        self.assertEqual(len(rahul_order[0].messages), 3)
        self.assertIn("dhai sau", rahul_order[0].text)

    def test_different_senders_do_not_merge(self):
        blocks = segment(parse_export(DASHED))
        self.assertEqual(len(blocks), 2)

    def test_time_gap_splits_block(self):
        text = (
            "[26/08/26, 9:00:00 AM] Rahul: 2 kilo chawal\n"
            "[26/08/26, 9:30:00 AM] Rahul: 1 packet chai\n"
        )
        blocks = segment(parse_export(text), window_seconds=180)
        self.assertEqual(len(blocks), 2)


class TestClassifier(unittest.TestCase):
    def test_orders_pass(self):
        for text in [
            "2 kilo chawal bhej do dhai sau ka udhaar",
            "1 litre tel aur namak, 120 rupaye cash",
            "3 packet biscuit 60 rupaye",
            "payment mila 500 UPI",
        ]:
            self.assertTrue(classify(text).is_order, text)

    def test_chatter_is_dropped(self):
        for text in ["ok", "thik hai", "namaste bhaiya", "haan boliye", "mil gaya"]:
            self.assertFalse(classify(text).is_order, text)

    def test_drop_rate_on_a_real_chat(self):
        cursor = ChatCursor(chat_id="rahul")
        result = ingest(BRACKETED, cursor)
        # 4 blocks; only the two real orders should survive.
        self.assertEqual(len(result.candidates), 2)
        self.assertGreater(result.drop_rate, 0.4)


class TestDateFilter(unittest.TestCase):
    def test_since_filters_older_messages(self):
        cursor = ChatCursor(chat_id="rahul")
        result = ingest(BRACKETED, cursor, since=datetime(2026, 8, 26, 10, 0, 0))
        self.assertEqual(result.new_messages, 1)
        self.assertIn("tel", result.candidates[0].text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
