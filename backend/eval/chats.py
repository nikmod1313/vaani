"""
Conversation-level accuracy: does a real WhatsApp thread become the right order?

    python -m eval.chats                  # dev set
    python -m eval.chats --set test       # the held-out set
    python -m eval.chats --verbose        # every miss, with what was predicted

`eval.run` scores single messages, and it is saturated -- 122/122 because
the engine was tuned on it until it passed. This scores what the app now
actually does with a chat: thread the turns, decide whether an order exists,
and resolve its items, quantities, total and payment mode.

HELD-OUT SETS ARE NEVER TUNED ON. `chats_dev.jsonl` is for finding failures
and fixing them. `chats_test.jsonl` was written before the first round of
engine changes; its failures were then read (to report them), so it is now
spent as a held-out set and kept for continuity. `chats_test2.jsonl` was
written before the second round and is the current held-out measure.
Tuning against a held-out set turns it into a second dev set and the number
it gives into fiction.

Scoring, per expected order:

  detected   a predicted order with the same direction that names at least
             one of the same items (or, for item-less entries, any order with
             the same direction)
  items      the same set of items -- names compared through ALIASES, so
             "sugar" and "cheeni" are the same thing, and a modifier counts
             when the label states one ("toor dal" is not "moong dal")
  quantity   every stated quantity and unit matches
  amount     the total matches to the paisa; null means "no price was given"
  mode       cash / upi / udhaar / unknown matches
  correct    all of the above at once -- the figure that matters, because a
             khata entry with the right items and the wrong total is wrong

Every engine output is also written to `chats_engine_output.json`, which the
Android `ChatBenchmarkParityTest` replays through the Kotlin port and holds
to identical answers.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.conversation import Turn, resolve_all  # noqa: E402

HERE = Path(__file__).parent
CUSTOMER = "Rahul Verma"
SHOP = "Sharma Kirana Store"
T0 = datetime(2026, 10, 27, 9, 0)

# Spellings and scripts of the same thing. Evaluation-only: the engine keeps
# the customer's own word, and this table decides what counts as "the same
# item" when scoring it.
ALIASES = [
    {"cheeni", "chini", "sugar", "shakkar", "चीनी"},
    {"aata", "atta", "flour", "आटा"},
    {"chawal", "rice", "चावल"},
    {"doodh", "dudh", "milk", "दूध"},
    {"pyaz", "pyaaz", "onion", "प्याज"},
    {"anda", "ande", "egg", "eggs", "अंडा", "अंडे"},
    {"kela", "kele", "banana", "केला"},
    {"tel", "oil", "तेल"},
    {"chai", "tea", "chaipatti", "patti", "चाय", "चायपत्ती"},
    {"sabun", "soap", "साबुन"},
    {"tala", "taala", "lock", "ताला"},
    {"kabza", "kabze", "hinge", "कब्ज़ा"},
    {"jeera", "zeera", "जीरा"},
    {"elaichi", "ilaichi", "इलायची"},
    {"dawai", "dava", "dawa", "medicine", "दवाई", "दवा"},
    {"bread", "ब्रेड"},
    {"coldrink", "cold drink", "colddrink"},
    {"pani", "water", "पानी"},
    {"seb", "apple", "सेब"},
    {"dal", "daal", "दाल"},
    {"chips", "wafers"},
    {"gobhi", "gobi", "phoolgobhi", "cauliflower"},
]
_ALIAS = {w: min(group) for group in ALIASES for w in group}


def canon(word: str | None) -> str:
    if not word:
        return ""
    w = word.lower().strip()
    return _ALIAS.get(w, w)


def label_key(name: str, modifier: str | None) -> tuple[str, str]:
    return canon(modifier), canon(name)


def expected_key(label: str) -> tuple[str, str]:
    parts = label.split()
    if len(parts) == 1:
        return "", canon(parts[0])
    return canon(" ".join(parts[:-1])), canon(parts[-1])


def load(name: str) -> list[dict]:
    path = HERE / f"chats_{name}.jsonl"
    with path.open(encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def to_turns(case: dict) -> list[Turn]:
    turns, ts = [], T0
    for i, row in enumerate(case["turns"]):
        who, body = row[0], row[1]
        gap = row[2] if len(row) > 2 else (0 if i == 0 else 1)
        ts = ts + timedelta(minutes=gap)
        shop = who == "s"
        turns.append(Turn(
            sender=SHOP if shop else CUSTOMER,
            body=body,
            timestamp=ts,
            is_shopkeeper=shop,
            content_hash=f"{case['id']}-{i}",
        ))
    return turns


def predict(case: dict) -> list[dict]:
    orders = resolve_all(to_turns(case), CUSTOMER, case.get("cat"))
    return [
        {
            "dir": o.direction,
            "amount": o.amount,
            "mode": o.mode,
            "items": [
                {
                    "name": i.name, "modifier": i.modifier, "quantity": i.quantity,
                    "unit": i.unit, "unit_price": i.unit_price,
                }
                for i in o.items
            ],
        }
        for o in orders
    ]


# --------------------------------------------------------------------------
# Matching and scoring
# --------------------------------------------------------------------------

def _pred_keys(order: dict) -> dict[tuple[str, str], dict]:
    return {label_key(i["name"], i["modifier"]): i for i in order["items"]}


def _matches(exp_key: tuple[str, str], pred: dict[tuple[str, str], dict]) -> dict | None:
    """
    An expected label against predicted items: the noun must agree, the
    modifier only if the label states one.

    Two forms identify the same product and count as the same item: a
    brand in front of the generic noun ("dettol" for "dettol soap"), and a
    medicine's strength after its name ("dolo" for "dolo 650").
    """
    mod, noun = exp_key
    for (pmod, pnoun), item in pred.items():
        base = pnoun.split()[0] if pnoun else pnoun
        if (pnoun == noun or base == noun) and (not mod or pmod == mod):
            return item
        if not mod and pmod == noun:
            return item
    return None


def _overlap(exp: dict, pred: dict) -> int:
    keys = _pred_keys(pred)
    return sum(1 for label in exp["items"] if _matches(expected_key(label), keys))


def score_order(exp: dict, pred: dict) -> dict[str, bool]:
    keys = _pred_keys(pred)
    found = {label: _matches(expected_key(label), keys) for label in exp["items"]}
    items_ok = all(found.values()) and len(keys) == len(exp["items"])

    qty_ok = items_ok
    if items_ok:
        for label, (qty, unit) in exp["items"].items():
            got = found[label]
            if qty is not None and (got["quantity"] is None or abs(got["quantity"] - qty) > 1e-6):
                qty_ok = False
            if unit is not None and got["unit"] != unit:
                qty_ok = False

    a, b = exp["amount"], pred["amount"]
    amount_ok = (a is None and b is None) or (
        a is not None and b is not None and abs(float(a) - float(b)) < 0.01
    )
    mode_ok = (exp["mode"] or "unknown") == (pred["mode"] or "unknown")
    return {
        "items": items_ok, "quantity": qty_ok, "amount": amount_ok, "mode": mode_ok,
        "correct": items_ok and qty_ok and amount_ok and mode_ok,
    }


def match(expected: list[dict], predicted: list[dict]) -> tuple[list, list, list]:
    """Greedy one-to-one pairing on direction and item overlap."""
    pairs, unused = [], list(range(len(predicted)))
    missed = []
    for exp in expected:
        best, best_overlap = None, -1
        for j in unused:
            pred = predicted[j]
            if pred["dir"] != exp["dir"]:
                continue
            overlap = _overlap(exp, pred)
            ok = overlap > 0 or (not exp["items"] and not pred["items"]) or (
                not exp["items"] and exp["dir"] == "payment"
            )
            if ok and overlap > best_overlap:
                best, best_overlap = j, overlap
        if best is None:
            missed.append(exp)
        else:
            pairs.append((exp, predicted[best]))
            unused.remove(best)
    extra = [predicted[j] for j in unused]
    return pairs, missed, extra


def evaluate(cases: list[dict], verbose: bool = False) -> dict:
    tp = fp = fn = 0
    fields = {k: 0 for k in ("items", "quantity", "amount", "mode", "correct")}
    exact_cases = 0
    outputs = {}

    for case in cases:
        predicted = predict(case)
        outputs[case["id"]] = predicted
        pairs, missed, extra = match(case["expect"], predicted)
        tp += len(pairs)
        fn += len(missed)
        fp += len(extra)

        case_ok = not missed and not extra
        problems = []
        for exp, pred in pairs:
            s = score_order(exp, pred)
            for k, v in s.items():
                fields[k] += int(v)
            if not s["correct"]:
                case_ok = False
                wrong = [k for k in ("items", "quantity", "amount", "mode") if not s[k]]
                problems.append(f"wrong {','.join(wrong)}: expected {exp} got {pred}")
        problems += [f"MISSED {m}" for m in missed]
        problems += [f"INVENTED {e}" for e in extra]
        exact_cases += int(case_ok)

        if verbose and problems:
            print(f"\n[{case['id']}] " + " | ".join(t[1] for t in case["turns"]))
            for p in problems:
                print("   ", p)

    expected_orders = tp + fn
    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / expected_orders if expected_orders else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "cases": len(cases),
        "expected_orders": expected_orders,
        "detection": {"precision": precision, "recall": recall, "f1": f1, "tp": tp, "fp": fp, "fn": fn},
        # Field accuracies are over EXPECTED orders: a missed order is wrong
        # on every field, not excused from the count.
        "fields": {k: v / expected_orders if expected_orders else 1.0 for k, v in fields.items()},
        "exact_cases": exact_cases,
        "outputs": outputs,
    }


def report(name: str, r: dict) -> None:
    d = r["detection"]
    print(f"\n== chats_{name}: {r['cases']} threads, {r['expected_orders']} expected orders ==")
    print(f"order detection   precision {d['precision']:.3f}  recall {d['recall']:.3f}  "
          f"F1 {d['f1']:.3f}   (tp {d['tp']}, invented {d['fp']}, missed {d['fn']})")
    for k in ("items", "quantity", "amount", "mode", "correct"):
        print(f"  {k:<9} {r['fields'][k]:.3f}")
    print(f"threads fully right: {r['exact_cases']}/{r['cases']} ({r['exact_cases'] / r['cases']:.1%})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="dev", choices=["dev", "test", "test2", "all"])
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--dump", action="store_true", help="write chats_engine_output.json")
    args = ap.parse_args()

    names = ["dev", "test", "test2"] if args.set == "all" else [args.set]
    dump = {}
    for name in names:
        r = evaluate(load(name), verbose=args.verbose)
        report(name, r)
        dump[name] = r["outputs"]
    if args.dump:
        (HERE / "chats_engine_output.json").write_text(
            json.dumps(dump, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8",
        )


if __name__ == "__main__":
    main()
