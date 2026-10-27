# VaaniLedger

Voice- and chat-driven *khata* for small Indian shopkeepers. A floating
assistant sits on top of WhatsApp, reads a customer's order message, extracts
the transaction, and asks for one tap to confirm.

No WhatsApp Business API anywhere on the critical path.

---

## Status

| Component | State |
|---|---|
| Extraction engine | **Working** — 181 tests, macro F1 1.000 (see caveat) |
| WhatsApp export ingestion | **Working** — parser, watermark, classifier |
| Ledger store | **Working** — SQLite, idempotent sync, aggregates |
| Spoken summary | **Working** — templates in hi / en / mr |
| Stage 2 (Claude) | **Wired, unmeasured** — needs credentials |
| Sarvam TTS | **Wired, unmeasured** — needs `SARVAM_API_KEY` |
| Correction store | **Working** — few-shot retrieval per shop |
| Eval harness | **Working** — `python -m eval.run [--llm]` |
| FastAPI endpoints | **All live** against a real store |
| Android | **Core loop works** — capture → extract → confirm → Room, verified on device |
| Dashboard | **Working** — zero-build, served at `/` |

---

## Quick start

```bash
cd backend && python3 -m unittest discover -s tests -t .
```

```bash
cd backend && python3 -m eval.run --verbose
```

**Android** — needs Android Studio's JDK and the SDK, both of which it finds
itself:

```bash
cd android && ./build-and-run.sh
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

122 labelled cases across ten categories.

```
FIELD           PREC   RECALL      F1
amount         1.000    1.000   1.000
mode           1.000    1.000   1.000
direction      1.000    1.000   1.000
items          1.000    1.000   1.000
                        macro F1   1.000

Exact match (all fields):  122/122  (100.0%)
```

### Read that number carefully

**100% means the dataset is saturated, not that the engine is finished.** We
wrote these 122 cases, then fixed the engine until it passed them — so the
set no longer discriminates. It is now a regression suite, not a measure of
capability.

Two things follow, and both are on the plan:

1. **The next 30 cases must come from someone who did not write the engine.**
   Ideally from real shopkeeper messages rather than our imagination.
2. **A held-out split.** Right now every case is a training case. The number
   worth reporting is accuracy on messages the engine has never been tuned
   against.

The honest claim today is: *"the engine handles every construction we have
thought to test, and here is the list."* That is worth something, but it is
not 100% accuracy in the wild, and the report should not say it is.

### Whole conversations, with a held-out split

The 122 cases above are single messages. What the app actually does is read
a **chat** — thread the turns, decide whether an order exists, and resolve its
items, total and payment mode. `eval/chats.py` measures that, on 91 WhatsApp
threads in Hinglish, English and Devanagari across six kinds of shop, about a
fifth of them chatter, questions, complaints or cancellations that must book
nothing.

They are split in two, **and only one half was ever tuned on**:
`chats_test.jsonl` was written before the engine was changed and scored only
to report the result.

```bash
cd backend && python3 -m eval.chats --set all          # add --verbose for every miss
```

| Held-out test set (45 threads, 35 orders) | Before | After |
|---|---|---|
| Orders found (recall) | 28.6% | **100%** |
| Orders invented (precision) | 66.7% | **94.6%** |
| Total correct | 20.0% | **94.3%** |
| Order completely right — items, quantities, total, mode | **8.6%** | **80.0%** |

**Second round (27 Oct), on a fresh held-out set.** The first test set's
failures were read to report them, which spends it as a held-out measure.
So before the second round of fixes a NEW set, `chats_test2.jsonl` (40
threads, 29 orders), was written and scored once as a baseline, then again
only after the fixes — its failures were never looked at in between.

| Fresh held-out set (`test2`) | Before round 2 | After |
|---|---|---|
| Orders invented (precision) | 93.5% | **100%** |
| Order completely right | **82.8%** | **86.2%** |

That the first round's 80% reappeared as 82.8% on a set written afterwards is
the useful evidence: the first round's gains generalise rather than fitting
the examples they were tuned on.

Round 2 fixed: questions about a future arrangement ("chahiye hoga kya?")
booked as orders; refund requests booked as payments; synonyms across a
thread ("chini" / "sugar") treated as two items; two-noun garments ("salwar
kameez"); paying with a note ("100 ka note, baaki lauta dena") read as
credit; and — found only by driving the live capture path on a device — a
phone number in a captured "Save (987) 650-0001?" banner booked as a Rs 987
bill. Phone numbers are now removed before any number is read.

One caveat on the 80.0%: two of its points come from the thousands-comma bug
("1,250" read as 250), which was found in an exploratory probe before the test
set existed but only fixed after the first scoring; without it the figure is
74.3%. The dev half now scores 100%, which — like the 122 — says only that it
was tuned on.

The failure classes the first held-out set exposed were all addressed in
round 2, through dev cases written for each, not by tuning on that set.
Run `python3 -m eval.chats --set test2 --verbose` to see what the fresh set
still gets wrong; it is deliberately not listed here, because listing it is
the first step towards fixing against it.

`ChatBenchmarkParityTest` replays every one of these threads — all three sets, 142 of them — through the
Kotlin engine on the phone and requires the identical answer, so the two
engines cannot quietly drift apart. Re-run `python3 -m eval.chats --set all
--dump` after any engine change to refresh what it compares against.

**The stage-2 lift is still unmeasured.** Running `--llm` needs credentials:

```bash
export ANTHROPIC_API_KEY=sk-...   # or: ant auth login
cd backend && .venv/bin/python -m eval.run --llm
```

---

## Layout

```
backend/
  app/
    main.py         FastAPI — every endpoint in docs/api.yaml
    store.py        SQLite ledger: idempotent sync, FIFO aging, seed data
    summary.py      spoken-summary templates (hi / en / mr)
    tts.py          Sarvam Bulbul v3 client + content-addressed audio cache
    extraction/
      numbers.py    Hindi number words, both directions
      rules.py      amount / mode / items / direction
      names.py      transliteration + fuzzy customer matching
      confidence.py per-field scoring and routing
      whatsapp.py   export parsing, watermark, segmentation, classifier
      llm.py        stage 2 — Claude, structured output, batch path
      corrections.py  Badlo pairs + per-shop few-shot retrieval
      pipeline.py   orchestrates the four stages
  eval/             122 labelled cases + scoring harness
  demo.py           end-to-end walkthrough, no install needed
  tests/            181 tests, stdlib unittest for the core
docs/api.yaml       the frozen contract everyone codes against
docs/*.pptx         progress-review deck
dashboard/          one HTML file, no build step, served at /
android/            builds to a 4.7MB debug APK
  MainActivity      permission flow + start/stop the floater
  overlay/          OverlayService, BubbleView, PanelView
  capture/          accessibility, notification listener, export share target
  data/             Room entities, DAOs, FIFO aging
  sync/             WorkManager sync worker
  reminder/         wa.me + upi:// deep links
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

## Traps already found and fixed — do not reintroduce these

Ten bugs so far, every one of which would have put a wrong number in
someone's ledger. All have regression tests. The instructive classes:

**Hindi words that are also verbs.** `do` means 2 but usually "send", so
*"chai bhej do, dhai sau"* parsed as `(2 + 2.5) × 100 = 450` instead of 250.
Same trap caught `diya` (an oil lamp, but overwhelmingly "gave" — every
payment booked a lamp purchase) and `daal` ("lentils", but `daal do` means
"put it in"). See `TestDoAmbiguity` and `TestVerbTraps`.

**A quantity is not a price.** `"2 kilo chawal bhej do"` booked ₹2 — every
number was consumed as an item quantity, then the amount picker grabbed one
back. A missing amount is recoverable; a wrong one is not. `TestQuantityIsNotMoney`.

**`\b` does not work on Devanagari.** Python excludes matras (े ा ि) from
`\w` because they are nonspacing marks, so a trailing `\b` never matches
after a word ending in one — which is most Hindi words. Every `\b…\b` Hindi
pattern was silently dead code. Use `DEVA_START`/`DEVA_END` in `rules.py`.
`TestDevanagariBoundaries`.

**Transliteration ate real vowels.** `प्रिया` became "priy" and `bhaiya`
became "bhaiy", which then dodged the honorific filter. Only the *inherent*
vowel is trimmed now — never a matra, never Latin input.

**Aging must follow the money.** Taking the oldest udhaar date outright ages
a balance from a debt already paid off, so every regular customer reads as
permanently overdue and the flag stops meaning anything. Payments settle
oldest-first. Duplicated deliberately in Kotlin `Aging.settle` — if the phone
and the dashboard disagree, the shopkeeper trusts neither.

**`Anthropic()` constructs fine with no credentials.** It only fails at
request time, so treating construction as availability made `eval.run --llm`
print "rules + claude-opus-5" over numbers the model never touched. The eval
now refuses to report stage-2 numbers when zero calls succeeded, and
`/health` states which stages are live.

**A fresh phone's first sync had no shop row.** Transactions and customers
saved fine, but `/summary` 404'd on a shop that demonstrably had data —
indistinguishable from data loss. Saving now guarantees the shop row.

## Next up

1. **Week 0 spike (Person A + B):** get `OverlayService` drawing over WhatsApp
   and `LedgerAccessibilityService` logging chat text on the demo phone. If
   this fails, fall back to notification listener + OCR and re-plan the same
   day.
2. Room schema + `docs/api.yaml` frozen with all four people present.
3. **Measure the stage-2 lift** — add credentials and run `eval.run --llm`.
4. Grow the eval dataset from 122 cases toward 150, weighted toward
   `real_world` — the weakest category at 50%.
5. **Have a Marathi speaker review `summary.py`'s `mr-IN` template** — the
   sentence frames are drafted, but the amounts still come out in Hindi
   number words. Ship it only once someone who speaks Marathi has read it.
6. **Open `android/` in Android Studio and make it build.** The Kotlin was
   written without a compiler on hand, so expect import and signature fixes.
   The wire format is the part that is already verified — every DTO in
   `net/Api.kt` was round-tripped against the running API.

### A note on the dashboard

It is one HTML file with no build step — no npm, no bundler, nothing to break
during a demo. The chart is hand-rolled SVG rather than Recharts. This is a
deliberate departure from the original plan, which said Next.js: with a team
new to the stack, a broken `npm run build` twenty minutes before a viva is a
worse outcome than a plainer chart. Porting to Next.js later is optional and
changes nothing server-side.
