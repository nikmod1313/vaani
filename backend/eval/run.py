"""
Extraction accuracy evaluation.

    python -m eval.run                 # rules-only baseline
    python -m eval.run --verbose       # show every miss

Reports field-level precision / recall / F1 plus a confidence-calibration
table -- does a 0.9 confidence actually mean 90% correct?

Run this weekly from Week 2 and put the numbers in the report. Almost no
student project reports measured accuracy; a stated limitation with data
behind it scores better than a vague claim of "high accuracy".
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.pipeline import extract  # noqa: E402

DATASET = Path(__file__).parent / "dataset.jsonl"
FIELDS = ("amount", "mode", "direction", "items")

# Every real message arrives with a WhatsApp sender attached, so the customer
# field is effectively always populated in production. Supplying one here
# keeps the calibration table honest -- without it the customer field scores
# 0.0 on every case and drags 25% of the confidence weight down, making the
# scores look far worse calibrated than they are.
EVAL_SENDER = "Rahul Verma"
EVAL_KNOWN_CUSTOMERS = [
    "Rahul Verma", "Priya Sharma", "Imran Khan", "Sunita Devi", "Mohan Lal",
]


def load(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def compare(fieldname: str, predicted, expected) -> bool:
    """Field-level correctness. Items compare as sets -- order is not meaning."""
    if fieldname == "items":
        pred_names = {str(i).split()[0].lower() for i in (predicted or [])}
        exp_names = {str(i).split()[0].lower() for i in (expected or [])}
        return pred_names == exp_names
    if fieldname == "amount":
        if predicted is None or expected is None:
            return predicted == expected
        return abs(float(predicted) - float(expected)) < 0.01
    return (predicted or "unknown") == (expected or "unknown")


class Counter:
    """Presence-aware counts, so 'correctly found nothing' is not a false positive."""

    def __init__(self) -> None:
        self.tp = self.fp = self.fn = self.tn = 0

    def record(self, predicted, expected, correct: bool) -> None:
        has_pred = predicted not in (None, [], "", "unknown")
        has_exp = expected not in (None, [], "", "unknown")

        if has_exp and has_pred:
            if correct:
                self.tp += 1
            else:
                self.fp += 1
                self.fn += 1
        elif has_exp and not has_pred:
            self.fn += 1
        elif not has_exp and has_pred:
            self.fp += 1
        else:
            self.tn += 1

    @property
    def precision(self) -> float:
        return self.tp / (self.tp + self.fp) if (self.tp + self.fp) else 1.0

    @property
    def recall(self) -> float:
        return self.tp / (self.tp + self.fn) if (self.tp + self.fn) else 1.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 2 * p * r / (p + r) if (p + r) else 0.0


def main() -> int:
    parser = argparse.ArgumentParser(description="VaaniLedger extraction eval")
    parser.add_argument("--verbose", "-v", action="store_true", help="show each miss")
    parser.add_argument("--dataset", type=Path, default=DATASET)
    args = parser.parse_args()

    rows = load(args.dataset)
    counters: dict[str, Counter] = {f: Counter() for f in FIELDS}
    exact_matches = 0
    calibration: dict[str, list[int]] = defaultdict(lambda: [0, 0])  # bucket -> [correct, total]
    misses: list[str] = []

    for row in rows:
        result = extract(
            row["text"],
            known_customers=EVAL_KNOWN_CUSTOMERS,
            sender_name=row.get("sender", EVAL_SENDER),
        )
        predicted = {
            "amount": result.amount,
            "mode": result.mode,
            "direction": result.direction,
            "items": result.items,
        }
        expected = row["expected"]

        all_correct = True
        for fieldname in FIELDS:
            correct = compare(fieldname, predicted[fieldname], expected.get(fieldname))
            counters[fieldname].record(predicted[fieldname], expected.get(fieldname), correct)
            if not correct:
                all_correct = False
                if args.verbose:
                    misses.append(
                        f"  #{row['id']:>2} {fieldname:<10} "
                        f"got={predicted[fieldname]!r:<28} want={expected.get(fieldname)!r}"
                    )

        if all_correct:
            exact_matches += 1

        bucket = f"{int(result.confidence['overall'] * 10) / 10:.1f}"
        calibration[bucket][1] += 1
        if all_correct:
            calibration[bucket][0] += 1

    # ---- report ---------------------------------------------------------
    print()
    print("=" * 62)
    print(f"  VaaniLedger extraction eval  ·  {len(rows)} cases  ·  rules-only")
    print("=" * 62)
    print()
    print(f"  {'FIELD':<12}{'PREC':>8}{'RECALL':>9}{'F1':>8}{'TP':>6}{'FP':>5}{'FN':>5}")
    print("  " + "-" * 55)

    macro_f1 = 0.0
    for fieldname in FIELDS:
        c = counters[fieldname]
        macro_f1 += c.f1
        print(
            f"  {fieldname:<12}{c.precision:>8.3f}{c.recall:>9.3f}{c.f1:>8.3f}"
            f"{c.tp:>6}{c.fp:>5}{c.fn:>5}"
        )
    macro_f1 /= len(FIELDS)

    print("  " + "-" * 55)
    print(f"  {'macro F1':<12}{macro_f1:>25.3f}")
    print()
    print(f"  Exact match (all fields):  {exact_matches}/{len(rows)}"
          f"  ({exact_matches / len(rows):.1%})")

    print()
    print("  CONFIDENCE CALIBRATION")
    print("  " + "-" * 55)
    print(f"  {'bucket':<10}{'n':>6}{'exact':>8}{'actual':>10}   ideal")
    for bucket in sorted(calibration):
        correct, total = calibration[bucket]
        actual = correct / total if total else 0.0
        flag = "  ok" if abs(actual - float(bucket)) <= 0.15 else "  DRIFT"
        print(f"  {bucket:<10}{total:>6}{correct:>8}{actual:>10.1%}   ~{bucket}{flag}")

    if args.verbose and misses:
        print()
        print("  MISSES")
        print("  " + "-" * 55)
        for line in misses:
            print(line)

    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
