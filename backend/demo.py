"""
End-to-end demo. No server, no install, no network.

    python3 demo.py

Walks the two paths the product actually has:

    1. Single capture  -- one message off a WhatsApp screen
    2. Bulk ingestion  -- a whole chat export, deduped and filtered

Useful for showing progress to the team, and as a dry run of the demo script.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from app.extraction.numbers import find_numbers, rupees_to_words
from app.extraction.pipeline import extract, extract_batch
from app.extraction.whatsapp import ChatCursor, ingest, parse_export
from app.store import LedgerStore
from app.summary import compose

KNOWN = ["Rahul Verma", "Priya Sharma", "Imran Khan", "Sunita Devi", "Mohan Lal"]

EXPORT = """[26/08/26, 9:38:02 AM] Rahul Verma: Namaste bhaiya
[26/08/26, 9:39:11 AM] Sharma Kirana: Haan boliye
[26/08/26, 9:41:23 AM] Rahul Verma: Bhaiya 2 kilo chawal bhej do
[26/08/26, 9:41:44 AM] Rahul Verma: aur 1 packet chai
[26/08/26, 9:42:02 AM] Rahul Verma: dhai sau ka udhaar likh lena
[26/08/26, 9:45:00 AM] Sharma Kirana: theek hai
[26/08/26, 10:15:00 AM] Priya Sharma: 1 litre tel aur namak, 120 rupaye cash
[26/08/26, 11:02:00 AM] Imran Khan: ok bhaiya
[26/08/26, 11:30:00 AM] Imran Khan: 3 packet biscuit aur maggi 5 packet, 210 nakad
[26/08/26, 12:10:00 PM] Sunita Devi: payment mila 500 phonepe se bhej diye
"""

BAR = "=" * 68
DASH = "-" * 68


def rule(title: str) -> None:
    print(f"\n{BAR}\n  {title}\n{BAR}\n")


def show(e) -> None:
    c = e.confidence
    print(f"  {c['route'].upper():<7} {c['overall']:.2f}   "
          f"{e.customer or '?':<14} Rs {e.amount or 0:<8.0f} "
          f"{e.mode:<8} {', '.join(e.items) or '-'}")
    if c["ask_about"]:
        for name in c["ask_about"]:
            print(f"{'':>16}? {name}: {c['fields'][name]['reason']}")


def main() -> None:
    # ---------------------------------------------------------------- 1
    rule("1  ·  NUMBER PARSING   the piece most likely to be wrong")
    for phrase in ["dhai sau", "dedh sau", "sava sau", "paune do sau",
                   "sadhe teen sau", "do hazaar paanch sau", "ढाई सौ"]:
        value = find_numbers(phrase)[0][0]
        print(f"  {phrase:<24} -> {value:>8.0f}")

    print("\n  and backwards, for the spoken summary:\n")
    for n in (4240, 1140, 250):
        print(f"  Rs {n:<21} -> {rupees_to_words(n)}")

    # ---------------------------------------------------------------- 2
    rule("2  ·  SINGLE CAPTURE   one message off the screen")
    text = ("Bhaiya 2 kilo chawal aur 1 packet chai bhej do, "
            "dhai sau ka udhaar likh lena")
    print(f'  "{text}"\n')
    print(DASH)
    show(extract(text, KNOWN, sender_name="Rahul Verma"))
    print(DASH)
    print("\n  Note: 'bhej do' is the verb 'send', not the number 2.")
    print("  Read naively this yields 450 instead of 250.")

    # ---------------------------------------------------------------- 3
    rule("3  ·  BULK INGESTION   a whole WhatsApp export")
    cursor = ChatCursor(chat_id="demo")
    result = ingest(EXPORT, cursor)

    print(f"  {result.total_messages} messages"
          f"  ->  {result.blocks} blocks"
          f"  ->  {len(result.candidates)} candidates"
          f"   ({result.drop_rate:.0%} dropped as chatter)\n")
    print(DASH)
    extractions = extract_batch(
        [b.text for b in result.candidates],
        KNOWN,
        [b.sender for b in result.candidates],
    )
    for e in extractions:
        show(e)
    print(DASH)

    auto = sum(1 for e in extractions if e.confidence["route"] == "auto")
    print(f"\n  \"Sab sahi hai\" would approve {auto} of {len(extractions)} "
          f"in one tap.")

    # ---------------------------------------------------------------- 4
    rule("4  ·  THE WATERMARK   re-importing must not double-count")
    cursor.advance(parse_export(EXPORT))
    again = ingest(EXPORT, cursor)
    print(f"  Same export, second run: {again.new_messages} new messages, "
          f"{len(again.candidates)} candidates.")
    print("\n  Deterministic content hashes, not a model call. A model that is")
    print("  99% right about 'have I seen this' still double-charges someone")
    print("  one time in a hundred.")

    # ---------------------------------------------------------------- 5
    rule("5  ·  THE SPOKEN SUMMARY   what Bulbul reads aloud")
    store = LedgerStore(":memory:")
    store.seed("sharma-kirana", days=90)
    summary = store.summary("sharma-kirana")

    print(f"  Rs {summary['today_sale']:.0f} sale   "
          f"Rs {summary['cash_in']:.0f} cash   "
          f"Rs {summary['udhaar_today']:.0f} udhaar   "
          f"Rs {summary['total_outstanding']:.0f} outstanding\n")

    for lang in ("hi-IN", "en-IN"):
        narration = compose(summary, lang)
        print(f"  {lang}   ({len(narration)} chars, cap is 2500)")
        for sentence in narration.text.split(". "):
            if sentence.strip():
                print(f"     {sentence.strip().rstrip('.')}.")
        print()

    print("  Every figure comes from a template filled by the store. No model")
    print("  restates a number -- \"about four thousand\" instead of Rs 4,240")
    print("  is a lie a listener has no way to catch.\n")
    store.close()


if __name__ == "__main__":
    main()
