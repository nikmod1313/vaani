"""Measurement, defects, commercial analysis, state and roadmap."""

from reportlab.lib.units import mm
from reportlab.platypus import PageBreak, Spacer
from blocks import Paragraph

from blocks import S, box, bug, bullets, code, h1, h3, para, stat_row, table


def measurement():
    f = []
    f += h1("How we measure, and what the numbers mean",
            kicker="Part IV · Section 11")
    f.append(Paragraph(
        "We built an evaluation harness: 122 messages, hand-labelled across "
        "ten categories, scored field by field. It runs in under a second and "
        "is re-run on every change.", S["Lead"]))
    f += table(
        ["Field", "Precision", "Recall", "F1"],
        [["amount", "1.000", "1.000", "1.000"],
         ["mode (cash / UPI / udhaar)", "1.000", "1.000", "1.000"],
         ["direction (sale / payment)", "1.000", "1.000", "1.000"],
         ["items", "1.000", "1.000", "1.000"],
         ["<b>macro F1</b>", "", "", "<b>1.000</b>"]],
        widths=[70 * mm, 34 * mm, 33 * mm, 33 * mm],
        align_right=(1, 2, 3))
    f.append(Paragraph(
        "Exact match across all four fields: 122 / 122 (100.0%).",
        S["Caption"]))
    f.append(Spacer(1, 4 * mm))

    f += box("Read that number carefully — and say this before anyone asks", [
        "<b>100% means the dataset is saturated, not that the engine is "
        "finished.</b> We wrote these 122 cases, then fixed the engine until "
        "it passed them. The set no longer discriminates: it is a regression "
        "suite now, not a measure of capability.",
        "The honest claim is <i>“the engine handles every construction we "
        "have thought to test, and here is the list.”</i> That is worth "
        "something — it is a great deal more than most projects can say — but "
        "it is not 100% accuracy in the wild, and this report does not say it "
        "is.",
        "Two things follow, and both are planned: the next cases must be "
        "written by <b>someone who did not write the engine</b>, ideally "
        "drawn from real shopkeeper messages; and there must be a "
        "<b>held-out split</b>, because at present every case is a training "
        "case.",
    ], tone="warn")

    f.append(h3("Why the baseline is the genuinely valuable artefact"))
    f.append(para(
        "Every figure above is <b>rules-only, with the language model "
        "switched off</b>. That is a real control group. When the model layer "
        "is measured, we will be able to state how much it helped rather than "
        "assert that it helps — the difference between an engineering claim "
        "and a marketing one. Very few projects at this scale retain a "
        "control condition, and it costs nothing to keep."))
    f.append(h3("What measurement bought us"))
    f.append(para(
        "The harness is not decoration. Of the twenty-plus defects fixed in "
        "this project, <b>nine were found by the evaluation harness alone</b> "
        "— not by reading code, not by manual testing. The next section lists "
        "them by class, because the classes generalise and the individual "
        "bugs do not."))
    f.append(PageBreak())
    return f


def defects():
    f = []
    f += h1("Every defect, and its lesson", kicker="Part IV · Section 12")
    f.append(Paragraph(
        "Twenty-plus defects found and fixed, each with a regression test. "
        "They fall into five classes. For a system that records what people "
        "owe, the useful column is not what broke but <b>what it would have "
        "cost</b>.", S["Lead"]))

    f.append(h3("Class 1 — words that are two things at once"))
    f += bug(1, "“do” means 2, but usually means “send”",
             "<i>“chai bhej do, dhai sau”</i> parsed as (2 + 2.5) × 100. Fixed "
             "with clause boundaries plus a verb-stem rule.",
             "A ₹450 entry instead of ₹250 — a 80% overcharge on a real "
             "customer, from the single most common word in an order message.",
             "A number word in a verb position is a verb. Hinglish is full of "
             "these and they must be resolved by context, not lexicon.")
    f += bug(2, "“diya” was in the item list",
             "It is an oil lamp, and overwhelmingly the verb “gave”. So "
             "<i>“phonepe kar diya”</i> booked a lamp purchase.",
             "A phantom line item on <b>every payment message</b> — the "
             "ledger accumulating purchases that never happened.",
             "Recall is not worth an unbounded false-positive rate. We "
             "dropped the item entirely rather than defend it.")
    f += bug(3, "“daal do” is not lentils, “aata hoon” is not flour",
             "<i>daal</i> and <i>aata</i> are real items, but "
             "<i>“khaate mein daal do”</i> means “put it in the account” and "
             "<i>“kal aata hoon”</i> means “I'll come tomorrow”.",
             "Groceries invented out of ordinary conversation, inflating what "
             "a customer appeared to owe.",
             "Ambiguous item words now require a quantity or unit beside "
             "them; unambiguous ones do not.")

    f.append(h3("Class 2 — money that was not money"))
    f += bug(4, "A quantity was reused as a price",
             "<i>“2 kilo chawal bhej do”</i> booked ₹2. Every number had been "
             "consumed as a quantity, then the amount picker fell through and "
             "took one back.",
             "A ₹250 sale recorded as ₹2 — the shop losing 99% of the value "
             "of that transaction, silently.",
             "A missing amount is recoverable, because the app asks. A wrong "
             "one is money out of someone's pocket. The picker now returns "
             "nothing rather than guessing.")
    f += bug(5, "“X ka Y” means “Y worth X”",
             "<i>“paune sau ka chai”</i> is seventy-five rupees of tea, not "
             "seventy-five teas. Read as a quantity, the price vanished into "
             "the item and the entry saved with no amount at all.",
             "Entries with an item and no value — an unrecorded sale, and an "
             "unrecoverable one once the message scrolls away.",
             "Grammar carries semantics. The Hindi possessive marks the "
             "number before it as money.")
    f += bug(6, "A measure was picked as the price",
             "In <i>“chai khatam ho gayi, 2 packet bhej do”</i> a clause "
             "break stopped the 2 reaching <i>chai</i>, so it became a ₹2 "
             "sale.",
             "Same as bug 4: near-total loss of value on that transaction.",
             "A number directly followed by a unit is a measure, full stop — "
             "regardless of whether it found its item.")
    f.append(PageBreak())

    f.append(h3("Class 3 — the tooling lied"))
    f += bug(7, "\\b does not work on Devanagari",
             "Python excludes Devanagari vowel signs from <font "
             "face='Courier'>\\w</font> because they are nonspacing marks, so "
             "a trailing <font face='Courier'>\\b</font> never matches after "
             "a word ending in one — which is most Hindi words. Every "
             "Devanagari pattern in the rules layer was silently dead code.",
             "Devanagari messages — a stated product requirement — were "
             "handled far worse than the test suite implied, and nothing "
             "reported an error.",
             "Regex primitives assume Latin script. Verify them per script, "
             "with a test that would fail if the pattern matched nothing.")
    f += bug(8, "A timestamp used an invisible space",
             "Android renders “11:07 PM” with U+202F, a narrow no-break "
             "space, which <font face='Courier'>\\s</font> does not match. "
             "The timestamp filter silently missed.",
             "Timestamps captured as order text, corrupting extraction input.",
             "Same class as bug 7. When a string filter mysteriously fails, "
             "print the bytes.")
    f += bug(9, "Anthropic() constructs fine with no credentials",
             "It only fails at request time. Treating construction as proof "
             "of availability made the evaluation print “rules + "
             "claude-opus-5” above numbers the model had never touched.",
             "A wrong conclusion heading straight into a report: “the model "
             "layer provided no measurable improvement”, when in fact it had "
             "never run.",
             "The eval now refuses to report stage-2 numbers when zero calls "
             "succeeded, and /health states which stages are live.")
    f += bug(10, "sort is not a version sort",
             "<font face='Courier'>sort | tail -1</font> picked "
             "<font face='Courier'>gradle-8.2</font> over "
             "<font face='Courier'>gradle-8.13</font>, because "
             "<font face='Courier'>'2' &gt; '1'</font>.",
             "The Android plugin rejected the old Gradle and the failure read "
             "like a code error, costing debugging time on healthy code.",
             "Use <font face='Courier'>sort -V</font>.")
    f += bug(11, "A filtered build log hid a failed build",
             "Piping Gradle through <font face='Courier'>grep '^e:'</font> "
             "showed nothing, and the install reported “Success” — having "
             "deployed a twenty-minute-old APK.",
             "Twenty minutes spent debugging code that was not running on the "
             "device.",
             "Never filter a build log you have not first watched fail.")
    f.append(PageBreak())

    f.append(h3("Class 4 — correct in code, wrong on screen"))
    f += bug(12, "Every customer looked 87 days overdue",
             "Aging ignored payments that had already cleared the debt "
             "(section&nbsp;10).",
             "Overdue flags carrying no information, and reminders that would "
             "have told people they owed money they had paid.",
             "Some bugs are invisible in an API response and obvious in a "
             "rendered list of people. Look at the screen.")
    f += bug(13, "A new phone's first sync 404'd",
             "Transactions and customers saved, but no <font face='Courier'>"
             "shops</font> row existed, so the dashboard could not find the "
             "shop. SQLite does not enforce foreign keys by default, so it "
             "succeeded silently.",
             "Indistinguishable from data loss on a user's first day — the "
             "single worst moment to look broken.",
             "Test the empty state, not only the seeded one.")
    f += bug(14, "The bubble drew, the letter inside it did not",
             "<font face='Courier'>onMeasure</font> called "
             "<font face='Courier'>setMeasuredDimension</font> without "
             "measuring children.",
             "Cosmetic, but on the single most visible element in the "
             "product.",
             "Invisible in code review, obvious in a screenshot.")
    f += bug(15, "Capture read the app's own setup screen",
             "<font face='Courier'>packageNames</font> in the accessibility "
             "config limits which apps send us <i>events</i>; it does not "
             "stop <font face='Courier'>rootInActiveWindow</font> returning a "
             "focused window from anywhere.",
             "A privacy failure as much as a correctness one — the product's "
             "central claim is that it reads WhatsApp and nothing else.",
             "Capture now enumerates windows for a supported chat window, "
             "wherever focus happens to be.")

    f.append(h3("Class 5 — silent failures after apparent success"))
    f += bug(16, "Sync failed after the server accepted the write",
             "<font face='Courier'>SyncResponse.transactions</font> was typed "
             "as strings, but the server sends real transaction objects where "
             "amount is a number. The POST succeeded, the row saved, and "
             "parsing the <i>response</i> threw.",
             "The entry was on the server while the phone showed it pending "
             "<b>forever</b> — the worst shape a sync bug can take, because "
             "both sides look wrong in different ways.",
             "Type a response to what the server actually sends. The same "
             "mistake in /corrections was caught by this precedent.")
    f += bug(17, "Every sync job reported FAILED and posted nothing",
             "<font face='Courier'>SyncWorker</font>'s constructor took an "
             "extra argument, and WorkManager's default factory constructs "
             "workers with exactly (Context, WorkerParameters).",
             "Entries never left the phone. The app looked entirely healthy; "
             "the failure lived only in a log nobody was reading.",
             "Take the dependency from a singleton rather than the "
             "constructor, unless a custom WorkerFactory is justified.")
    f.append(PageBreak())
    return f


def commercial():
    f = []
    f += h1("Unit economics and cost model", kicker="Part V · Section 13")
    f.append(Paragraph(
        "The target customer is extremely price-sensitive. A design whose "
        "marginal cost scales with usage is a design that cannot be given "
        "away, and giving it away is how this category gets adopted.",
        S["Lead"]))
    f += table(
        ["Cost driver", "Design response", "Effect"],
        [["Model calls per message",
          "Rules resolve most messages with no model call; a rule-based "
          "classifier drops ~50% of chat traffic before extraction; bulk "
          "import batches N blocks into one request.",
          "Marginal cost per order falls well below one model call"],
         ["WhatsApp messaging fees",
          "Assisted reminders via a wa.me deep link (decision&nbsp;2).",
          "Zero — no Business API, no per-conversation fee"],
         ["Speech recognition",
          "Android's on-device recogniser first; a hosted model only as an "
          "online upgrade.",
          "Zero for the default path"],
         ["Voice synthesis",
          "Content-addressed audio cache keyed on text + language + speaker.",
          "One synthesis per distinct summary, not per dashboard open"],
         ["Backend hosting",
          "SQLite and a single FastAPI process; Postgres only when "
          "multi-tenancy demands it.",
          "Negligible at pilot scale"]],
        widths=[38 * mm, 82 * mm, 50 * mm])
    f += box("The honest gap in this section", [
        "These are <b>design decisions that bound cost</b>, not measured "
        "costs. The model layer and the voice model are both wired and "
        "credential-gated, so the actual rupees-per-shop-per-month figure is "
        "<b>unmeasured</b>.",
        "Measuring it is a half-day of work once API keys exist, and it "
        "belongs in the next revision of this report rather than being "
        "estimated here. An invented number would be worse than an "
        "acknowledged gap.",
    ], tone="warn")
    f.append(PageBreak())

    f += h1("Distribution, and why the Play Store is closed",
            kicker="Part V · Section 14")
    f.append(Paragraph(
        "This is the direct cost of decision&nbsp;1, and it is stated plainly "
        "rather than discovered by a reader.", S["Lead"]))
    f.append(para(
        "Google restricts the accessibility APIs to applications whose "
        "<b>core function is assisting users with disabilities</b>. "
        "VaaniLedger uses them to read a chat screen for a shopkeeper's "
        "convenience, which is not that. An app using them for our purpose "
        "should expect rejection or removal from the Play Store."))
    f += table(
        ["Channel", "Viability", "Notes"],
        [["Google Play", "Closed",
          "Accessibility policy. Not a technical obstacle — a policy one, so "
          "no amount of engineering changes it."],
         ["Direct APK / sideload", "Viable now",
          "Fits the actual distribution reality: these products are sold "
          "shop-to-shop by field agents who install on the spot."],
         ["Enterprise / MDM", "Viable",
          "Appropriate for a distributor or FMCG partner deploying across a "
          "network of shops."],
         ["Rebuild on Export Chat only", "Viable, degraded",
          "The chat-export lane needs no accessibility permission at all and "
          "would be Play-compliant, at the cost of the one-tap capture that "
          "makes the product distinctive."]],
        widths=[45 * mm, 30 * mm, 95 * mm])
    f.append(para(
        "The last row is worth noting as a genuine strategic option rather "
        "than a footnote: the bulk-import path alone is a viable, "
        "Play-distributable product with a slower interaction model. Keeping "
        "three independent capture lanes means that pivot stays available "
        "without an architectural rewrite."))
    f.append(PageBreak())
    return f


def state_and_roadmap():
    f = []
    f += h1("What is true today", kicker="Part VI · Section 15")
    f.append(Paragraph(
        "Three categories that a status table usually blurs, kept apart "
        "deliberately. Conflating them is how a project ends up claiming that "
        "compiled-but-never-called code works.", S["Lead"]))

    f.append(h3("Verified — executed, with evidence"))
    f += table(
        ["Component", "Evidence"],
        [["Extraction engine", "189 automated tests; macro F1 1.000 on 122 "
          "labelled cases"],
         ["Bulk import and dedup", "10 messages → 8 blocks → 4 orders; "
          "re-importing the same export yields 0 new"],
         ["Ledger and sync", "Replay reports accepted=0, duplicates=1; row "
          "count unchanged"],
         ["Web dashboard", "Browser-verified in light and dark, at 375px, "
          "with a clean console"],
         ["Spoken summary", "386 characters of Hindi, amounts spelled out, "
          "within Bulbul's 2,500-character limit"],
         ["The floating overlay", "Draws over the home screen and other apps; "
          "survives leaving the app"],
         ["Chat capture", "Reads exactly the real messages off a live chat "
          "app's view tree, zero chrome"],
         ["Full loop on device", "Wiped database → panel shows ₹0 → capture → "
          "confirm → panel shows ₹250 sale / ₹250 udhaar, matching the server"],
         ["Badlo → learning loop", "Edit sheet prefilled, correction POSTed, "
          "pair stored: customer '' → 'Rahul Verma'"],
         ["Khata screen", "Kul baaki ₹250 · Rahul Verma ₹250 · entries with "
          "source tags"],
         ["Assisted reminder", "Draft composed and intent fired at "
          "com.whatsapp, with share-sheet fallback"]],
        widths=[48 * mm, 122 * mm])

    f.append(h3("Written but never executed"))
    f += box("This must not be described as working", [
        "<b>ScreenCaptureService</b> (screenshot + OCR) is a stub. "
        "<b>MessageNotificationListener</b> logs but does not yet stage to "
        "Room.",
        "<b>Voice</b>: the recogniser launches correctly with hi-IN, but the "
        "transcript path has never run end to end, because this emulator has "
        "no speech backend. It needs a physical phone.",
    ], tone="cut")

    f.append(h3("Unmeasured"))
    f.append(para(
        "The Claude extraction layer and Sarvam voice synthesis are both "
        "wired and credential-gated. Everything reported above is therefore "
        "the rules-only baseline. <font face='Courier'>/health</font> reports "
        "which stages are live, so a degraded run cannot be mistaken for a "
        "full one."))
    f.append(PageBreak())

    f += h1("Risks and limitations we are not hiding",
            kicker="Part VI · Section 16")
    f += table(
        ["Risk", "Severity", "Mitigation / status"],
        [["Capture untested against real WhatsApp",
          "High",
          "The largest open unknown. Proven against another chat app's view "
          "tree; WhatsApp's is its own. A one-minute diagnostic dump exists "
          "for when it breaks."],
         ["WhatsApp version drift breaks capture silently",
          "High",
          "Structural heuristics rather than view IDs reduce it. A “read zero "
          "messages” self-check that surfaces to the user is still needed."],
         ["OEM battery managers kill the overlay service",
          "High",
          "Xiaomi, Oppo and Vivo are aggressive. Untested on physical "
          "hardware — a real phone left running overnight is required."],
         ["No authentication",
          "High",
          "Anyone who knows a shop_id can read and write that shop's ledger. "
          "Disqualifying before any real pilot."],
         ["100% accuracy is on a saturated set",
          "Medium",
          "Stated in section 11. Needs externally-written cases and a "
          "held-out split."],
         ["Marathi template is a draft",
          "Medium",
          "Sentence frames are Marathi but amounts still render in Hindi "
          "number words. Must not ship without a Marathi speaker's review."],
         ["Play Store closed",
          "Medium",
          "Section 14. Sideload and enterprise are viable; an export-only "
          "build is a fallback."],
         ["Devanagari OCR at ~85%",
          "Low",
          "Accessibility is the primary path precisely because of this."],
         ["Overpayment not modelled",
          "Low",
          "Paying more than owed floors the balance at zero rather than "
          "carrying an advance."]],
        widths=[52 * mm, 22 * mm, 96 * mm])
    f += box("Why this section exists", [
        "Every one of these could have been omitted. A reader who finds a "
        "limitation we did not mention discounts everything else in the "
        "report; a reader who sees them named first has reason to trust the "
        "parts we do claim.",
    ], tone="indigo")
    f.append(PageBreak())

    f += h1("Roadmap", kicker="Part VI · Section 17")
    f.append(h3("Immediate — before any demonstration"))
    f += bullets([
        "Remove Google Messages from the accessibility scope; it is there "
        "only so the parser could be proven against a real view tree",
        "Disable the debug tree dump, which logs chat contents",
        "Move the API base address off the emulator-only 10.0.2.2",
    ])
    f.append(h3("Next — completing the product"))
    f += bullets([
        "Run capture against real WhatsApp on a physical phone",
        "Leave the overlay running overnight on an OEM device",
        "Measure the model layer against the rules-only control group",
        "Screenshot + OCR capture, and notification staging into Room",
        "Voice transcript path end to end on real hardware",
    ])
    f.append(h3("Before a pilot with one real shopkeeper"))
    f += bullets([
        "Authentication and an HTTPS deployment",
        "Tested Room migrations — that database holds his money",
        "Backup and export, so a lost phone is not a lost khata",
        "WhatsApp version-drift detection that surfaces to the user",
        "Crash reporting, and real error states throughout",
        "Evaluation cases written by someone outside the team",
    ])
    f.append(h3("Before general availability"))
    f += bullets([
        "A distribution decision from section 14",
        "Privacy policy and consent flow under India's DPDP Act",
        "Multi-tenant backend with monitoring",
        "A measured cost model (section 13)",
        "Onboarding that works with no developer present",
    ])
    f.append(PageBreak())

    f += h1("How to run it", kicker="Part VI · Section 18")
    f.append(h3("Backend, dashboard, tests and evaluation"))
    f += code(
"""# tests, accuracy evaluation and an end-to-end demo — no install needed
./run.sh

# the above, then the API and dashboard on :8000
./run.sh serve""")
    f.append(h3("Android"))
    f += code(
"""# builds, boots the emulator, installs and launches
cd android && ./build-and-run.sh

# then, in the app:
#   1. both permission cards should read CHALU HAI
#   2. tap "Bubble chalu karein"
#   3. press Home and open a chat — the bubble floats on top
#   4. tap the bubble, then "Yeh likh lo\"""")
    f.append(h3("Measuring the model layer"))
    f += code(
"""export ANTHROPIC_API_KEY=sk-...
cd backend && .venv/bin/python -m eval.run --llm""")
    f.append(Spacer(1, 6 * mm))
    f.append(Paragraph(
        "Repository layout, module-by-module notes and the current status "
        "table are maintained in <font face='Courier'>README.md</font> and "
        "<font face='Courier'>docs/STATUS.md</font>, which are updated with "
        "the code rather than at the end.", S["Caption"]))
    return f
