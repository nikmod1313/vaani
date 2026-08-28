"""Technical architecture and the engine."""

from reportlab.lib.units import mm
from reportlab.platypus import PageBreak, Spacer
from blocks import Paragraph

from blocks import S, box, bullets, code, h1, h2, h3, para, table


def architecture():
    f = []
    f += h1("System overview", kicker="Part III · Section 5")
    f.append(Paragraph(
        "Three tiers. The phone is authoritative; the backend holds the "
        "engine and the shared view; the dashboard is a reader.", S["Lead"]))
    f += code(
"""ANDROID  (Kotlin, 3,755 lines)
  OverlayService ....... the floating bubble, SYSTEM_ALERT_WINDOW
    |- BubbleView ...... draggable, snaps to the screen edge
    |- PanelView ....... today's figures, read from Room
    |- ConfirmSheetView  per-field confidence, "Sahi hai" / "Badlo"
    |- EditSheetView ... the human overrules the engine
    |- KhataView ....... balances, entries, reminder buttons
  capture/
    |- LedgerAccessibilityService   reads the chat window
    |- ChatScraper ..... node tree -> messages
    |- ImportExportActivity ....... WhatsApp "Export chat" target
  data/  Room: shops, customers, transactions, cursors, corrections
  sync/  SyncWorker: idempotent, client-generated UUIDs
        |
        |  REST over HTTP
        v
BACKEND  (Python, FastAPI + SQLite, 3,728 lines)
  /extract .......... rules -> Claude -> per-field confidence
  /ingest/export .... bulk chat import with watermark dedup
  /sync ............. idempotent, bidirectional
  /corrections ...... the learning loop
  /summary/narrate .. templated text -> Sarvam Bulbul v3
        |
        v
DASHBOARD  (one HTML file, no build step, served at /)""")
    f.append(para(
        "Every input path has an on-device fallback. Speech recognition runs "
        "locally, the rules layer runs locally, and the ledger is local. "
        "The consequence is that <b>the core loop never requires a network</b> "
        "— a network improves extraction quality and shares data, but its "
        "absence degrades the product rather than stopping it."))
    f.append(PageBreak())

    # ---- engine --------------------------------------------------------
    f += h1("The extraction engine", kicker="Part III · Section 6")
    f.append(Paragraph(
        "Turning <i>“2 kilo chawal aur 1 packet chai bhej do, dhai sau ka "
        "udhaar likh lena”</i> into a ledger row. Four stages, in order.",
        S["Lead"]))
    f += table(
        ["Stage", "What it does", "Why it is where it is"],
        [["1 · Rules",
          "Hindi number words, payment keywords, quantities, item names, "
          "sale vs payment direction. Regex and lookup tables.",
          "Free per call, works offline, exactly repeatable. Resolves most "
          "real messages without a model."],
         ["2 · Model",
          "Claude, given only the fields the rules could not settle, plus "
          "the shop's own customer and item vocabulary.",
          "Handles phrasings nobody anticipated. Scoped narrowly so it "
          "cannot overrule arithmetic that already succeeded."],
         ["3 · Confidence",
          "Per-field score from layer agreement, field completeness and "
          "name-match strength.",
          "Decides what the shopkeeper is asked. Not a log line — it drives "
          "the interface."],
         ["4 · Names",
          "Fuzzy match plus transliteration against the shop's customers.",
          "So <i>Rahul</i> and <i>राहुल</i> are one customer, and the khata "
          "does not sprout duplicates."]],
        widths=[26 * mm, 68 * mm, 76 * mm])

    f.append(h3("The learning loop"))
    f.append(para(
        "Every time the shopkeeper taps <b>Badlo</b> and corrects a field, "
        "the pair <font face='Courier'>(raw_text → corrected_json)</font> is "
        "stored. Similar pairs are retrieved later and supplied to the model "
        "as few-shot examples <i>for that shop specifically</i>."))
    f.append(para(
        "Per-shop matters commercially. One shop's <i>chai</i> is a "
        "ten-rupee packet; another's is a ninety-rupee box. A single shared "
        "model cannot be right for both, and a system that learns each shop's "
        "own vocabulary gets better the longer it is used — which is the "
        "retention mechanic as much as the accuracy mechanic."))
    f += box("Why the correction path had to be built, not deferred", [
        "For most of the project the <b>Badlo</b> button was a placeholder "
        "that dismissed the sheet. That single dead button quietly invalidated "
        "the entire learning claim: no corrections meant no training pairs, "
        "which meant nothing to learn from.",
        "It is a useful reminder that a feature described in a plan and a "
        "feature on a live code path are different things, and only one of "
        "them is worth claiming.",
    ], tone="warn")
    f.append(PageBreak())

    # ---- numbers -------------------------------------------------------
    f += h1("Hindi numbers, in both directions", kicker="Part III · Section 7")
    f.append(Paragraph(
        "Shopkeepers do not say “two hundred and fifty”. They say "
        "<i>dhai sau</i>. Hindi has a system of fractional multipliers with "
        "no English equivalent, and it is used constantly for money.",
        S["Lead"]))
    f += table(
        ["Spoken", "Structure", "Value"],
        [["dhai sau", "2.5 × 100", "250"],
         ["dedh sau", "1.5 × 100", "150"],
         ["sava sau", "(1 + ¼) × 100", "125"],
         ["paune do sau", "(2 − ¼) × 100", "175"],
         ["sadhe teen sau", "(3 + ½) × 100", "350"],
         ["do hazaar paanch sau", "2×1000 + 5×100", "2,500"],
         ["ढाई सौ", "Devanagari, same value", "250"]],
        widths=[52 * mm, 78 * mm, 40 * mm], align_right=(2,))
    f.append(para(
        "Hindi numerals are irregular all the way to a hundred, so there is "
        "no generating rule — 1 to 99 is a lookup table, with the many "
        "romanisations shopkeepers actually type "
        "(<i>dhai</i>, <i>dhaai</i>, <i>adhai</i>) mapped onto one value."))
    f += box("Why the parser also runs backwards", [
        "The spoken dashboard needs ₹4,240 rendered as <i>“chaar hazaar do "
        "sau chalees rupaye”</i>. That is the same table read in the other "
        "direction.",
        "Implementing both directions in one module means they cannot drift "
        "apart, and it enables a round-trip test: parse everything we can "
        "say, and say everything we can parse. That test catches divergence "
        "that would otherwise appear silently, months later, in a spoken "
        "figure nobody checked.",
    ], tone="good")
    f.append(PageBreak())

    # ---- capture -------------------------------------------------------
    f += h1("Reading the chat", kicker="Part III · Section 8")
    f += table(
        ["Path", "Mechanism", "Trade-off"],
        [["A · Accessibility",
          "Walks WhatsApp's view hierarchy and reads message text directly.",
          "Exact text, no OCR errors, unaffected by FLAG_SECURE. Primary."],
         ["B · Screenshot + OCR",
          "MediaProjection to a bitmap, then on-device ML Kit.",
          "Roughly 85% on Devanagari, but works on <i>any</i> app — payment "
          "confirmations, bank SMS, a photo of a paper khata."]],
        widths=[36 * mm, 62 * mm, 72 * mm])

    f.append(h3("Why the parser leans on shape, not view IDs"))
    f.append(para(
        "The obvious way to find message bubbles is to match WhatsApp's view "
        "IDs. We deliberately do not depend on that. <b>Those IDs are not a "
        "public contract</b> — they change between versions, and a parser "
        "tuned to one build reads zero messages on the next, silently. A "
        "silent zero is the worst failure available here: the app looks "
        "healthy and simply stops recording income."))
    f.append(para("So the scraper reasons structurally:"))
    f += bullets([
        "a message is a <b>leaf</b> node carrying real text",
        "an <b>editable</b> node is the compose box, never a message",
        "a <b>clickable</b> short label is a button, not a bubble",
        "outgoing bubbles sit right of centre, incoming left",
        "timestamps and date separators look like timestamps and dates",
        "messages are ordered by <b>vertical position</b>, because chat lists "
        "are commonly laid out reversed and traversal order is not chat order",
    ])
    f += box("Tested against a real app — and what that does not prove", [
        "This parser was originally written against an <i>imagined</i> "
        "hierarchy, which is the most dangerous kind of code: plausible, "
        "reviewable, and never executed.",
        "WhatsApp cannot be installed on a bare emulator without a Play "
        "sign-in, so we tested against Google Messages driven by injected "
        "SMS — the same accessibility API and the same class of view tree. "
        "The parser went from reading <b>12 nodes (4 real messages plus 8 "
        "pieces of chrome) to exactly the 4 real messages</b>, and those four "
        "fed to the engine produce ₹250 / udhaar / chawal 2kg + chai 1packet "
        "at 0.89 confidence.",
        "<b>This does not prove WhatsApp works.</b> It proves the code path "
        "is sound and it shook out three real bugs. Running it against real "
        "WhatsApp on a physical phone remains the largest open unknown in the "
        "project, and is listed as such in section&nbsp;16 rather than "
        "quietly assumed.",
    ], tone="warn")
    f.append(PageBreak())

    # ---- bulk ----------------------------------------------------------
    f += h1("Bulk ingestion and deduplication", kicker="Part III · Section 9")
    f.append(Paragraph(
        "A busy shop receives thirty orders across eight chats before lunch. "
        "Tapping capture thirty times is not a product, so whole "
        "conversations can be imported at once.", S["Lead"]))
    f += table(
        ["Lane", "Mechanism", "Why it exists"],
        [["Export Chat",
          "WhatsApp's own export, shared to us as a .txt",
          "Official, complete, up to 40,000 messages, needs no permission "
          "at all"],
         ["Scroll-harvest",
          "Accessibility scrolls the chat backwards, reading as it goes",
          "In place, triggered by voice — no app switch"],
         ["Notification listener",
          "Stages incoming messages as they arrive",
          "The backlog is ready before he asks for it"]],
        widths=[36 * mm, 66 * mm, 68 * mm])
    f += code(
"""10 messages
  -> watermark ....... drop anything already processed
  -> segment ......... group consecutive messages by sender (3 min window)
  -> classify ........ 8 blocks -> 4 candidates   (50% dropped as chatter)
  -> batch extract ... 4 blocks in ONE model call, not four
  -> review queue .... "Sab sahi hai" approves the confident ones at once""")
    f.append(para(
        "Segmentation matters more than it looks. Orders routinely arrive "
        "across three messages — <i>“2 kilo chawal”</i> / <i>“aur 1 chai”</i> "
        "/ <i>“udhaar likh lena”</i> — and mean nothing apart. Grouping "
        "consecutive messages from one sender inside a three-minute window "
        "reassembles the order before extraction sees it."))
    f.append(para(
        "The classifier that drops 50% is deliberately cheap and rule-based. "
        "Most chat traffic is <i>“ok”</i>, <i>“thik hai”</i>, "
        "<i>“namaste”</i>; filtering it before any model call is the "
        "difference between one API call and twenty, which is a direct "
        "operating cost (section&nbsp;13)."))
    f.append(PageBreak())

    # ---- ledger --------------------------------------------------------
    f += h1("The ledger, sync and khata aging", kicker="Part III · Section 10")
    f.append(h3("Idempotent sync"))
    f.append(para(
        "Transaction IDs are <b>generated on the phone</b>, not assigned by "
        "the server. The phone is where the entry was confirmed, so the phone "
        "owns its identity. That single choice is what makes sync safe: "
        "replaying a batch after a dropped connection updates rows instead of "
        "creating a second set."))
    f += code(
"""first send   ->  accepted=1  duplicates=0
identical replay ->  accepted=0  duplicates=1
row count unchanged""")
    f.append(h3("Khata aging, and a bug we nearly shipped"))
    f += box("Aging must follow the money, not the calendar", [
        "“Days overdue” originally took the date of the oldest unpaid "
        "<i>udhaar</i>. On seeded data <b>every single customer showed 85–89 "
        "days overdue</b>, which no real shop looks like.",
        "The cause: a customer who had cleared a debt months ago was still "
        "being aged <i>from that cleared debt</i>. Every regular customer "
        "therefore read as permanently overdue, the flag stopped carrying "
        "information, and the reminder it drives would have told someone "
        "“pending 85 days” about money they had already paid — the exact "
        "relationship damage decision&nbsp;2 exists to prevent.",
        "Payments now settle debts oldest-first, and a balance is aged from "
        "the oldest debt still uncovered. The seeded spread moved to 14–63 "
        "days. Worth noting the totals were <b>correct throughout</b>; only "
        "the rendered list revealed it.",
    ], tone="cut")
    f.append(para(
        "This logic is deliberately duplicated in Kotlin rather than fetched "
        "from the server, because the khata must be correct offline. If the "
        "phone and the dashboard ever disagree about how many days someone is "
        "overdue, the shopkeeper stops trusting both — so the two "
        "implementations reference each other in comments and share the same "
        "test cases."))
    f.append(PageBreak())
    return f
