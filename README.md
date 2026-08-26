# VaaniLedger

Voice- and chat-driven *khata* for small Indian shopkeepers. A floating
assistant sits on top of WhatsApp, reads a customer's order message, extracts
the transaction, and asks for one tap to confirm.

No WhatsApp Business API anywhere on the critical path.

---

## Status

| Component | State |
|---|---|
| Extraction engine | **Working** — 156 tests, macro F1 0.926 |
| WhatsApp export ingestion | **Working** — parser, watermark, classifier |
| Ledger store | **Working** — SQLite, idempotent sync, aggregates |
| Spoken summary | **Working** — templates in hi / en / mr |
| Stage 2 (Claude) | **Wired, unmeasured** — needs credentials |
| Sarvam TTS | **Wired, unmeasured** — needs `SARVAM_API_KEY` |
| Correction store | **Working** — few-shot retrieval per shop |
| Eval harness | **Working** — `python -m eval.run [--llm]` |
| FastAPI endpoints | **All live** against a real store |
| Android | Manifest + overlay + accessibility skeletons |
| Dashboard | **Working** — zero-build, served at `/` |

---

## Quick start

```bash
cd backend && python3 -m unittest discover -s tests -t .
```

```bash
cd backend && python3 -m eval.run --verbose
```

```bash
./run.sh serve
```

Then open <http://localhost:8000> for the dashboard, or
<http://localhost:8000/docs> for the API contract.

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

Items is the weakest field and that is expected — it is the one stage 2
exists to lift.

**The stage-2 lift is not measured yet.** Running `--llm` needs credentials:

```bash
export ANTHROPIC_API_KEY=sk-...   # or: ant auth login
cd backend && .venv/bin/python -m eval.run --llm
```

That comparison — rules-only vs rules+Claude on the same 30 cases — is the
single best number to put in the report, because the rules-only run is a real
control group. **Re-run the eval every week and keep the number.** A stated
limitation with data behind it scores better than a vague claim of high
accuracy.

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
    llm.py          stage 2 — Claude, structured output, batch path
    corrections.py  Badlo pairs + per-shop few-shot retrieval
    pipeline.py     orchestrates the four stages
  eval/             labelled dataset + scoring harness
  tests/            121 tests, stdlib unittest for the core
docs/api.yaml       the frozen contract everyone codes against
android/            manifest, overlay service, accessibility service
dashboard/          not started
```

---

## Four ideas worth understanding before you edit anything

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

**4. The voice model never restates a number.** `summary.py` fills a
per-language template from real aggregates and spells amounts out with the
parser's own number table, run backwards. Bulbul only reads what the store
already computed. An LLM that paraphrases ₹4,240 as "about four thousand" has
lied to someone about their money, and a listener cannot catch it.

---

## Three traps already found and fixed

**`do` means 2, but usually it's a verb.** In *"chai bhej do, dhai sau"* the
parser first read `do dhai sau` as `(2 + 2.5) × 100 = 450` instead of 250.
Fixed with clause boundaries plus a verb-stem rule. See `TestDoAmbiguity`.

**Trailing-vowel trimming ate real vowels.** `प्रिया` transliterated to
"priy", and `bhaiya` to "bhaiy" — which then dodged the honorific filter. Only
the *inherent* vowel gets trimmed now, never a matra, and never Latin input.

**`Anthropic()` constructs fine with no credentials.** It only fails at
request time, so treating construction as proof of availability made
`eval.run --llm` print "rules + claude-opus-5" over numbers the model had
never touched — every call failed, the pipeline degraded to rules-only, and
the header still claimed otherwise. The eval now refuses to report stage-2
numbers when zero calls succeeded, and `/health` states which stages are live.

All three have regression tests. Do not remove them.

---

## Next up

1. **Week 0 spike (Person A + B):** get `OverlayService` drawing over WhatsApp
   and `LedgerAccessibilityService` logging chat text on the demo phone. If
   this fails, fall back to notification listener + OCR and re-plan the same
   day.
2. Room schema + `docs/api.yaml` frozen with all four people present.
3. **Measure the stage-2 lift** — add credentials and run `eval.run --llm`.
4. Grow the eval dataset from 30 cases toward 150.
5. **Have a Marathi speaker review `summary.py`'s `mr-IN` template** — the
   sentence frames are drafted, but the amounts still come out in Hindi
   number words. Ship it only once someone who speaks Marathi has read it.
6. Room schema + the Kotlin sync client (Person B).

### A note on the dashboard

It is one HTML file with no build step — no npm, no bundler, nothing to break
during a demo. The chart is hand-rolled SVG rather than Recharts. This is a
deliberate departure from the original plan, which said Next.js: with a team
new to the stack, a broken `npm run build` twenty minutes before a viva is a
worse outcome than a plainer chart. Porting to Next.js later is optional and
changes nothing server-side.
