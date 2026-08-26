# VaaniLedger

Voice- and chat-driven *khata* for small Indian shopkeepers. A floating
assistant sits on top of WhatsApp, reads a customer's order message, extracts
the transaction, and asks for one tap to confirm.

No WhatsApp Business API anywhere on the critical path.

---

## Status

| Component | State |
|---|---|
| Extraction engine | **Working** — 94 tests, macro F1 0.926 |
| WhatsApp export ingestion | **Working** — parser, watermark, classifier |
| Eval harness | **Working** — `python -m eval.run` |
| FastAPI endpoints | Extraction live; sync + summary stubbed |
| Android | Manifest + overlay + accessibility skeletons |
| Dashboard | Not started |

---

## Quick start

```bash
cd backend && python3 -m unittest discover -s tests -t .
```

```bash
cd backend && python3 -m eval.run --verbose
```

```bash
cd backend && pip install -r requirements.txt && uvicorn app.main:app --reload
```

Try it:

```bash
curl -s localhost:8000/extract -H 'content-type: application/json' -d '{"text":"2 kilo chawal aur 1 packet chai, dhai sau ka udhaar likh lena","sender_name":"Rahul Verma","known_customers":["Rahul Verma","Priya Sharma"]}' | python3 -m json.tool
```

---

## Current accuracy (rules-only baseline, no LLM layer)

```
FIELD           PREC   RECALL      F1
amount         0.926    1.000   0.962
mode           1.000    1.000   1.000
direction      0.952    0.909   0.930
items          0.789    0.833   0.811
                        macro F1   0.926

Exact match (all fields):  24/30  (80.0%)
```

Items is the weakest field and that is expected — it is the one the LLM layer
exists to lift. **Re-run the eval every week and keep the number in the
report.** A stated limitation with data behind it scores better than a vague
claim of high accuracy.

---

## Layout

```
backend/
  app/extraction/
    numbers.py      Hindi number words, both directions
    rules.py        amount / mode / items / direction
    names.py        transliteration + fuzzy customer matching
    confidence.py   per-field scoring and routing
    whatsapp.py     export parsing, watermark, segmentation, classifier
    pipeline.py     orchestrates the four stages
  eval/             labelled dataset + scoring harness
  tests/            94 tests, stdlib unittest — no install needed
docs/api.yaml       the frozen contract everyone codes against
android/            manifest, overlay service, accessibility service
dashboard/          not started
```

---

## Three ideas worth understanding before you edit anything

**1. The engine runs without a model.** The LLM layer is injected and
optional. Rules resolve most entries offline, and `extract()` degrades to
rules-only if a model call fails rather than losing the entry. The offline
promise is load-bearing — a pipeline that needs the network is one that dies
on a shop counter with no signal.

**2. Deduplication is a watermark, not NLP.** `ChatCursor` holds the last
processed timestamp plus a rolling window of SHA-1 content hashes. Never ask a
model whether it has seen a message before — being right 99% of the time means
duplicate money in someone's khata one time in a hundred.

**3. Confidence is per field.** "Amount certain, item guessed" is the common
case, and a single blended score throws that away. Only the weak field gets
asked about.

---

## Two traps already found and fixed

**`do` means 2, but usually it's a verb.** In *"chai bhej do, dhai sau"* the
parser first read `do dhai sau` as `(2 + 2.5) × 100 = 450` instead of 250.
Fixed with clause boundaries plus a verb-stem rule. See `TestDoAmbiguity`.

**Trailing-vowel trimming ate real vowels.** `प्रिया` transliterated to
"priy", and `bhaiya` to "bhaiy" — which then dodged the honorific filter. Only
the *inherent* vowel gets trimmed now, never a matra, and never Latin input.

Both have regression tests. Do not remove them.

---

## Next up

1. **Week 0 spike (Person A + B):** get `OverlayService` drawing over WhatsApp
   and `LedgerAccessibilityService` logging chat text on the demo phone. If
   this fails, fall back to notification listener + OCR and re-plan the same
   day.
2. Room schema + `docs/api.yaml` frozen with all four people present.
3. Grow the eval dataset from 30 cases toward 150.
4. Wire the LLM layer behind `pipeline.LLMLayer`.
5. Sarvam Bulbul v3 for the spoken summary — **server-side only**, the key
   never ships in the APK.
