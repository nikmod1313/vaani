# Context prompt for a fresh Claude

Paste everything below the line into a new claude.ai conversation. It is
self-contained — it assumes no memory of how the project was built.

Keep it current: if it drifts from the code it becomes actively misleading,
which is worse than having no brief at all.

---

I'm working on **VaaniLedger**, a college capstone project (team of 4, graded
out of 100). I need help continuing it. Here is the full context.

## What it is

A voice- and chat-driven *khata* (credit ledger) for small Indian shopkeepers.

A neighbourhood kirana shop runs on credit — most sales are *udhaar*, recorded
in a paper notebook. Digital ledger apps exist but shopkeepers don't use them,
because typing an English form mid-rush costs more than scribbling two words.

**The key insight:** the order is already on his phone. Customers send it on
WhatsApp — *"Bhaiya 2 kilo chawal aur 1 packet chai bhej do, dhai sau ka
udhaar likh lena."* Everything the ledger needs is in that message. The
product's only job is to move it into a ledger without him retyping it.

**How:** a floating bubble sits on top of WhatsApp. He taps it while looking
at the order; the app reads the chat on screen, extracts the transaction,
shows a confirm sheet, and saves on one tap.

## The decision that shaped everything

The original design used a WhatsApp Business bot. We abandoned it in week one:
a verified WhatsApp Business Account needs business documentation and takes
weeks with no guaranteed date. That would put **someone else's approval
process on the critical path of a project with a fixed deadline.**

The overlay approach needs no API and no approval. Reminders got the same
treatment — instead of a bot messaging customers, the app *drafts* the message
with a UPI link and opens WhatsApp pre-filled; the shopkeeper taps send. Side
benefit: no message ever leaves the phone without a human seeing it.

## Architecture

```
ANDROID (Kotlin, ~3,250 lines)
  OverlayService ...... floating bubble, SYSTEM_ALERT_WINDOW
  capture/ ............ AccessibilityService reads WhatsApp; ChatScraper
                        turns the node tree into messages
  ConfirmSheetView .... per-field confidence, "Sahi hai" / "Badlo"
  Room ................ offline source of truth
  SyncWorker .......... idempotent sync, client-generated UUIDs
        | REST
BACKEND (Python FastAPI + SQLite, ~3,730 lines)
  /extract ............ rules -> Claude -> confidence scoring
  /ingest/export ...... bulk WhatsApp chat import
  /sync ............... idempotent, bidirectional
  /summary/narrate .... spoken daily summary via Sarvam Bulbul v3
        |
DASHBOARD ............. one HTML file, no build step, served at /
```

The phone owns the truth; sync is an optimisation. A shop counter often has
no signal, so every input path has an on-device fallback and the core loop
never requires a network.

## The extraction engine — four stages

1. **Deterministic rules.** Hindi number words, payment keywords, quantities,
   items, sale-vs-payment. Offline, free, exact.
2. **Claude, narrowly scoped.** Told only which fields the rules could not
   settle. Forbidden from overruling resolved ones.
3. **Confidence, per field.** >0.85 auto-fill · 0.50–0.85 ask about that field
   only · <0.50 fall back to buttons.
4. **Name resolution.** Fuzzy match + transliteration, so `Rahul` and `राहुल`
   are one customer.

Rules come first deliberately: offline, zero per-call cost, and deterministic —
`dhai sau` is 250 every time, and this layer decides how much money goes into
someone's ledger.

Hindi has fractional multipliers with no English equivalent:
`dhai sau`=250, `dedh sau`=150, `sava sau`=125, `paune do sau`=175,
`sadhe teen sau`=350. Numerals are irregular to 100, so 1–99 is a lookup
table. The parser also runs **backwards** (4240 → "chaar hazaar do sau
chalees") for the spoken summary — one module, so the two directions cannot
drift.

## Current state — be precise about these three categories

**Verified working (run, with evidence):**
- Extraction engine: 181 tests, macro F1 1.000 on 122 labelled cases
- WhatsApp export import, watermark dedup, order classifier
- SQLite ledger, idempotent sync, FIFO khata aging
- Web dashboard (browser-verified: light, dark, mobile, clean console)
- Spoken summary templates (hi / en / mr)
- **The floater** — draws over other apps on an emulator
- **Chat capture** — reads real messages off a live chat app's view tree
- **Full loop on device**: capture → POST /extract → confirm sheet → Room →
  sync → dashboard. Verified from a wiped DB: panel showed ₹0 / "Aaj koi entry
  nahi", then ₹250 sale / ₹250 udhaar / "1 entries · sab synced", matching the
  server.
- Khata screen (balances + entries with source tags)
- Voice entry wired (requests mic, launches recogniser with hi-IN)

**Written but never executed — do not describe as working:**
- `ReminderLinks` (wa.me + upi:// builder) is never called
- "Badlo" on the confirm sheet dismisses with a placeholder toast — there is
  no edit screen
- `ScreenCaptureService` (OCR) and `MessageNotificationListener` are stubs
- Voice: the transcript path has never run end to end (the emulator has no
  speech backend)

**Unmeasured:**
- Stage 2 (Claude) — needs `ANTHROPIC_API_KEY`. Everything above is the
  rules-only baseline, which is a real control group.
- Sarvam TTS — needs `SARVAM_API_KEY`
- Cost per shop per month

## About that 100% accuracy — say this before anyone asks

**It means the dataset is saturated, not that the engine is finished.** We
wrote the 122 cases, then fixed the engine until it passed them. The set is a
regression suite now, not a measure of capability.

The honest claim: *"the engine handles every construction we have thought to
test, and here is the list."* Two things are still needed: cases written by
someone who did not write the engine (ideally real shopkeeper messages), and a
held-out split.

## Traps already found — 20+ bugs, all with regression tests

Grouped, because the classes are the useful part:

**Words that are two things at once.** `do` means 2 but usually the verb
"send" — *"chai bhej do, dhai sau"* parsed as ₹450 instead of ₹250. Same trap
caught `diya` (oil lamp / "gave"), `daal` (lentils / "put in"), `aata`
(flour / "I come").

**Money that wasn't money.** A quantity got reused as a price, so *"2 kilo
chawal bhej do"* booked ₹2. And "X *ka* Y" is Hindi for "Y worth X" — read as
a quantity, the price vanished entirely.

**The tooling lied.** `\b` doesn't work on Devanagari (Python excludes matras
from `\w`, so a trailing `\b` never matches after most Hindi words — every
Devanagari regex was silently dead code). Android renders "11:07 PM" with
U+202F, which `\s` doesn't match. `sort` isn't a version sort, so it picked
gradle-8.2 over 8.13.

**Correct in code, wrong on screen.** Every customer looked 87 days overdue
because aging ignored payments that had cleared the debt. A new phone's first
sync 404'd. The bubble drew but the letter inside it didn't.

**Silent failures.** `Anthropic()` constructs fine with no credentials and
only fails at request time, so the eval printed "rules + claude-opus-5" over
numbers the model never touched. `SyncWorker` took a constructor argument
WorkManager can't supply, so every sync job reported FAILED and posted
nothing. `SyncResponse` was typed as strings but the server sends numbers, so
sync failed *after* the server had accepted the write — the row was saved and
the phone showed it pending forever.

## How to run it

```bash
# Terminal 1 — backend (must run; the app calls it to extract)
cd capstone && ./run.sh serve          # dashboard at localhost:8000

# Terminal 2 — the app
cd capstone/android && ./build-and-run.sh
```

In the app: both permission cards must read CHALU HAI (they open Settings —
neither can be granted in-app), then tap "Bubble chalu karein". Press Home,
open a chat, tap the bubble, tap "Yeh likh lo".

`./run.sh` alone runs tests + accuracy eval + an end-to-end demo with no
install needed.

## What's left

**Tier 1 (the capstone):** a real "Badlo" edit screen; customer detail;
reminder composer calling `ReminderLinks`; **capture against real WhatsApp on
a real phone** (only tested against another chat app so far — this is the
largest remaining unknown); a real phone left running overnight, since OEM
battery managers kill overlay services.

**Tier 2 (one real shopkeeper):** authentication — today anyone who knows a
`shop_id` can read and write that shop's ledger; HTTPS deployment; tested Room
migrations; backup/export; WhatsApp version-drift detection; crash reporting.

**Tier 3 (many users):** the Play Store is closed to this app (accessibility
APIs are restricted to apps whose core function is assisting users with
disabilities), so sideload or enterprise only. Plus DPDP Act compliance,
multi-tenant backend, measured cost model.

## Before any build leaves the team

- Remove `com.google.android.apps.messaging` from `accessibility_config.xml` —
  it is there only so the parser could be tested against a real view tree
- Turn off the debug tree dump; it logs chat contents
- Move `API_BASE` off the emulator-only `10.0.2.2`

## How I'd like you to work

- Tell me plainly when something is unverified rather than assuming it works
- Keep the distinction between verified / written-but-never-run / unmeasured
- If you find a bug, explain what it would have cost in the real world — this
  is someone's money
- Don't overclaim in anything I might show an examiner
