"""Front matter, the problem, and the decision log."""

from reportlab.lib.units import mm
from reportlab.platypus import PageBreak, Spacer
from blocks import Paragraph

from blocks import (S, bullets, box, code, decision, h1, h2, h3, para,
                    stat_row, table)


def front_matter():
    f = []
    f.append(Spacer(1, 42 * mm))
    f.append(Paragraph("VaaniLedger", S["TitleBig"]))
    f.append(Spacer(1, 5 * mm))
    f.append(Paragraph(
        "A voice- and chat-driven <i>khata</i> for small Indian shopkeepers"
        "<br/>Technical report: architecture, decisions, and the reasoning "
        "behind them", S["TitleSub"]))
    f.append(Spacer(1, 14 * mm))
    f += stat_row([("189", "automated tests"), ("1.000", "macro F1"),
                   ("122", "labelled cases"), ("20+", "defects found")])
    f.append(Spacer(1, 4 * mm))
    f.append(Paragraph(
        "Capstone project &nbsp;·&nbsp; team of four &nbsp;·&nbsp; 29 August 2026"
        "<br/>7,483 lines across a Python backend, an Android client and a web "
        "dashboard &nbsp;·&nbsp; 24 commits", S["Caption"]))
    f.append(PageBreak())

    # ---- executive summary --------------------------------------------
    f += h1("Executive summary", kicker="Read this first")
    f.append(Paragraph(
        "VaaniLedger turns a WhatsApp order into a ledger entry without the "
        "shopkeeper typing anything. A floating bubble sits on top of "
        "WhatsApp; he taps it while reading a customer's message, and the app "
        "reads the chat off the screen, extracts the transaction, and asks "
        "for one tap to confirm.", S["Lead"]))
    f.append(para(
        "The project is complete end to end on a device: capture, extraction, "
        "a confirm sheet with per-field confidence, an editable correction "
        "path that feeds a learning loop, an offline-first ledger, idempotent "
        "sync to a server, a web dashboard, assisted payment reminders, and a "
        "spoken daily summary. The extraction engine carries 189 automated "
        "tests and is measured against a hand-labelled dataset rather than "
        "asserted to work."))
    f.append(para(
        "This report is organised around <b>decisions</b> rather than "
        "features. Nine of them shaped the product, and for each one it "
        "records what was at stake, what the alternatives were, what we chose "
        "and — most usefully — what that choice cost. A decision log that "
        "records only outcomes is a list, not reasoning."))

    f += box("The single most consequential decision", [
        "The original design used a WhatsApp Business bot. We abandoned it in "
        "week one, because a verified WhatsApp Business Account requires "
        "business documentation and takes weeks to approve with no guaranteed "
        "date.",
        "That would have placed <b>a third party's approval process on the "
        "critical path of a project with a fixed, graded deadline</b>. No "
        "amount of good engineering downstream recovers from a dependency "
        "like that. Everything distinctive about the product — the overlay, "
        "the on-screen capture, the assisted reminders — follows from "
        "removing it.",
    ], tone="good")

    f.append(para(
        "The other honest headline is about the accuracy figure. The engine "
        "scores 1.000 macro F1 on our 122-case dataset, and that number means "
        "<b>the dataset is saturated, not that the engine is finished</b>. We "
        "wrote the cases, then fixed the engine until it passed them. Section "
        "&nbsp;11 explains exactly what the number does and does not support, "
        "because a reader who discovers that themselves discounts everything "
        "else in the report."))
    f.append(PageBreak())

    # ---- contents ------------------------------------------------------
    f += h1("Contents")
    toc = [
        ("Part I", "The problem and the market", ""),
        ("1", "Who this is for, and what it costs them today", "4"),
        ("2", "Why existing ledger apps fail commercially", "5"),
        ("3", "The insight the product rests on", "6"),
        ("Part II", "The decision log", ""),
        ("4", "Nine decisions and the reasoning behind each", "7"),
        ("Part III", "Technical architecture", ""),
        ("5", "System overview", "13"),
        ("6", "The extraction engine", "14"),
        ("7", "Hindi numbers, in both directions", "16"),
        ("8", "Reading the chat", "17"),
        ("9", "Bulk ingestion and deduplication", "19"),
        ("10", "The ledger, sync and khata aging", "20"),
        ("Part IV", "Measurement and quality", ""),
        ("11", "How we measure, and what the numbers mean", "22"),
        ("12", "Every defect, and its lesson", "24"),
        ("Part V", "Commercial analysis", ""),
        ("13", "Unit economics and cost model", "28"),
        ("14", "Distribution, and why the Play Store is closed", "29"),
        ("Part VI", "State, risk and roadmap", ""),
        ("15", "What is true today", "30"),
        ("16", "Risks and limitations we are not hiding", "31"),
        ("17", "Roadmap", "32"),
        ("18", "How to run it", "33"),
    ]
    for num, title, page in toc:
        if page:
            f.append(Paragraph(
                f"<font color='#8B91A0'>{num}</font>&nbsp;&nbsp;&nbsp;{title}"
                f"<font color='#8B91A0'> &nbsp;·&nbsp; {page}</font>",
                S["TOCItem"]))
        else:
            f.append(Spacer(1, 3 * mm))
            f.append(Paragraph(f"<b>{title}</b>", S["H3"]))
    f.append(PageBreak())
    return f


def part_one():
    f = []
    f += h1("Who this is for, and what it costs them today",
            kicker="Part I · Section 1")
    f.append(Paragraph(
        "A <i>kirana</i> is the neighbourhood grocery shop that most Indian "
        "households buy from daily. India has roughly 12 million of them. The "
        "defining commercial fact about one is that it runs on credit.",
        S["Lead"]))
    f.append(para(
        "A regular customer does not pay per visit. He takes goods through "
        "the week or the month and settles later — this is <i>udhaar</i>, and "
        "for a typical kirana it is not a minor line item but a large share of "
        "turnover. The shop is therefore running an unsecured lending "
        "business alongside a retail one, with no software, no credit "
        "assessment, and no systematic collection process."))
    f.append(para(
        "The record of all this is a paper notebook. That notebook is the "
        "shop's entire accounts receivable, and it has properties no business "
        "would accept if it were framed as a system design:"))
    f += bullets([
        "<b>Single point of failure.</b> One notebook, no copy. Water, fire "
        "or simple loss destroys the record of every rupee owed.",
        "<b>Unqueryable.</b> Answering “how much does Rahul owe?” means "
        "leafing through months of entries by hand, so in practice it is "
        "answered from memory.",
        "<b>No collection discipline.</b> There is no list of who is overdue "
        "and by how long, so chasing depends on the shopkeeper happening to "
        "remember at the moment the customer is standing in front of him.",
        "<b>Disputes are unwinnable.</b> If a customer disagrees with a "
        "figure, there is a handwritten line and two memories.",
    ])
    f += box("What the pain actually is, commercially", [
        "The cost is not the bookkeeping effort. It is <b>working capital "
        "trapped in uncollected udhaar</b>, plus the amount silently written "
        "off because nobody remembered to ask.",
        "That reframing matters for the product: the winning feature is not a "
        "prettier ledger, it is <b>making collection systematic</b> — which is "
        "why overdue flagging and assisted reminders are core rather than "
        "nice-to-have.",
    ], tone="indigo")
    f.append(PageBreak())

    f += h1("Why existing ledger apps fail commercially",
            kicker="Part I · Section 2")
    f.append(Paragraph(
        "Digital khata apps exist, some with very large download numbers. "
        "Shopkeepers install them and then largely stop using them. "
        "Understanding why is the whole basis of our design.",
        S["Lead"]))
    f += table(
        ["Failure mode", "Why it kills adoption"],
        [["Data entry at the worst possible moment",
          "The entry must be made mid-rush with a queue waiting. An app that "
          "asks for a form costs perhaps thirty seconds; a notebook costs "
          "three. The notebook wins every single time, and one skipped entry "
          "makes the whole ledger untrustworthy."],
         ["English-first, form-shaped interfaces",
          "The user thinks and speaks in Hindi or Hinglish. A form with "
          "labelled fields imposes a mental model from accounting software on "
          "someone who has never used accounting software."],
         ["Double entry of data that already exists",
          "The order arrived as a WhatsApp message. Asking him to read it and "
          "retype it is asking him to do the same work twice."],
         ["Trust asymmetry",
          "The notebook is his. An app that silently records numbers he "
          "cannot verify is a worse instrument for money he is owed, not a "
          "better one."]],
        widths=[52 * mm, 118 * mm])
    f.append(para(
        "Every one of these is a <b>friction problem, not a feature gap</b>. "
        "The implication is that adding features cannot win; the only thing "
        "that can win is removing the typing. That constraint set the entire "
        "product direction, and it is why the capture path — not the ledger — "
        "received the majority of the engineering effort."))
    f.append(PageBreak())

    f += h1("The insight the product rests on", kicker="Part I · Section 3")
    f.append(Paragraph(
        "The order is already on his phone.", S["Lead"]))
    f.append(para(
        "Customers do not phone in orders any more; they send them on "
        "WhatsApp. A real one looks like this:"))
    f += code(
        '"Bhaiya 2 kilo chawal aur 1 packet chai bhej do,\n'
        ' dhai sau ka udhaar likh lena"\n\n'
        '  who     -> the WhatsApp contact who sent it\n'
        '  what    -> 2 kg rice, 1 packet tea\n'
        '  how much-> dhai sau = 250\n'
        '  terms   -> udhaar (credit), "likh lena" = write it down')
    f.append(para(
        "Everything the ledger needs is in that message, already typed, by "
        "the customer, for free. The problem is therefore not data entry at "
        "all — <b>the data exists and simply never reaches the ledger</b>."))
    f.append(para(
        "That reframing is what makes the product tractable. The job is not "
        "to build a better form; it is to build a bridge roughly three taps "
        "long between a message and a ledger row. It also explains why the "
        "hardest engineering in this project sits in two unglamorous places: "
        "reading another app's screen reliably, and parsing Hinglish numbers "
        "correctly."))
    f += box("Why the confirm step survives despite the friction argument", [
        "Having argued that friction kills adoption, we then deliberately "
        "added a step: nothing is ever saved without a tap.",
        "The reasoning is that the two frictions are not comparable. Typing "
        "an entry is <b>repeated work</b>; confirming one is <b>a glance</b>. "
        "And the alternative — an app that silently writes numbers into a "
        "ledger about money owed — fails the trust test that already kills "
        "the incumbents. One wrong silent entry costs more trust than a "
        "hundred confirmations cost effort.",
    ], tone="warn")
    f.append(PageBreak())
    return f


def decision_log():
    f = []
    f += h1("Nine decisions and the reasoning behind each",
            kicker="Part II · Section 4")
    f.append(Paragraph(
        "Each record below states the situation, the alternatives, the "
        "choice, the business reasoning, and what the choice cost. The last "
        "field is the one usually missing from decision logs, and the one "
        "that makes them worth re-reading.", S["Lead"]))
    f.append(Spacer(1, 3 * mm))

    f += decision(
        1, "Overlay on top of WhatsApp, not a WhatsApp Business bot",
        context=(
            "The natural design is a bot: the shopkeeper forwards or messages "
            "an order to a bot number, it extracts the transaction and he "
            "confirms in the chat. It needs no new app surface and users "
            "already understand it."),
        options=(
            "(a) WhatsApp Business API bot; (b) an Android overlay that reads "
            "the chat already on screen; (c) manual copy-paste into a "
            "standalone app."),
        chosen=(
            "(b) — a floating bubble drawn over WhatsApp that reads the chat "
            "via Android's accessibility API."),
        reasoning=(
            "The bot requires a <b>verified</b> WhatsApp Business Account. "
            "Verification needs business documentation and takes weeks with "
            "no committed date. For a project with a fixed graded deadline "
            "that puts a third party's approval queue on the critical path — "
            "an unbounded schedule risk we could neither estimate nor "
            "mitigate. There is also a recurring cost: WhatsApp Business "
            "messaging is priced per conversation, so a bot-based design "
            "carries a per-shop monthly cost that scales with usage before "
            "the product has revenue. The overlay has neither property: no "
            "approval, no per-message cost, and it works with the WhatsApp "
            "the shopkeeper already has."),
        tradeoff=(
            "A large one, and it is structural. Accessibility-API apps cannot "
            "be distributed through the Play Store (section&nbsp;14), so this "
            "decision traded a schedule risk for a distribution constraint. "
            "It also makes capture dependent on WhatsApp's internal view "
            "hierarchy, which is not a public contract and can change with "
            "any update. We judged an unbounded schedule risk worse than a "
            "known, mitigable distribution problem — a capstone that misses "
            "its deadline scores zero regardless of its architecture."))

    f += decision(
        2, "Assisted reminders, not automated ones",
        context=(
            "Collection is the real commercial pain (section&nbsp;1), so "
            "reminders are core. The obvious implementation sends them "
            "automatically."),
        options=(
            "(a) automated sending via the WhatsApp Business API; (b) SMS via "
            "a gateway; (c) the app drafts the message and opens WhatsApp "
            "pre-filled for the shopkeeper to send."),
        chosen=(
            "(c) — compose the message with the amount and a UPI payment "
            "link, open WhatsApp pre-filled, and let him press send."),
        reasoning=(
            "Partly forced by decision&nbsp;1: automated sending needs the "
            "same API we removed. But it is the better design independently. "
            "These messages are debt collection directed at the shopkeeper's "
            "own neighbours and regulars — the relationship is the asset, and "
            "a mistimed or wrongly-addressed automated message damages it "
            "permanently. Assisted sending means <b>no message about money "
            "owed ever leaves the phone without a human seeing it</b>. It "
            "also works with any customer today, with no opt-in, no template "
            "approval and no per-message fee."),
        tradeoff=(
            "It does not scale to hundreds of reminders, and it cannot run "
            "unattended. For a shop with a few dozen credit customers that "
            "ceiling is far above real usage, so the constraint is "
            "theoretical at this stage."))

    f += decision(
        3, "Deterministic rules first, the language model second",
        context=(
            "Extraction could be a single call to a capable model, which "
            "would handle Hinglish, quantities and payment terms with very "
            "little code."),
        options=(
            "(a) model-only; (b) rules-only; (c) rules first, with the model "
            "filling only what rules cannot resolve."),
        chosen="(c).",
        reasoning=(
            "Three reasons that compound, and only one is about accuracy. "
            "<b>Unit economics:</b> extraction runs on every order message a "
            "shop receives — call it 30–60 a day. A model call on each is a "
            "recurring per-shop cost on a product whose customers are "
            "extremely price-sensitive; a rule costs nothing per call, and "
            "rules alone resolve the large majority of real messages. "
            "<b>Availability:</b> rules run with no network, and a shop "
            "counter frequently has no signal — a model-only design fails "
            "precisely when the shopkeeper needs it. <b>Determinism:</b> "
            "<i>dhai sau</i> is 250 every single time, whereas a model is 250 "
            "almost every time. This layer decides how much money goes into "
            "someone's ledger, and 'almost' is the wrong property for that."),
        tradeoff=(
            "Considerably more code, and a lexicon of Hindi number words, "
            "payment keywords and item names to maintain. It also caps "
            "recall: rules cannot generalise to phrasings nobody anticipated, "
            "which is exactly the gap the model layer exists to close."))

    f += decision(
        4, "The phone is the source of truth; sync is an optimisation",
        context=(
            "A server-authoritative design is simpler: one database, no "
            "conflict handling, no local schema to migrate."),
        options=(
            "(a) server-authoritative with a thin client; (b) offline-first "
            "with the phone authoritative and background sync."),
        chosen="(b).",
        reasoning=(
            "Connectivity at a shop counter is unreliable, and the moments "
            "that matter most — mid-transaction, customer waiting — are "
            "exactly when a network round trip is least tolerable. A "
            "server-authoritative app is unusable at the moment of use, which "
            "is a product-defining failure rather than an inconvenience. "
            "Making the phone authoritative also means a backend outage "
            "degrades the product rather than stopping it."),
        tradeoff=(
            "Sync must be idempotent, which forced client-generated "
            "transaction IDs and careful reasoning about replay. It also "
            "means the on-device schema needs real migrations, because that "
            "database holds the shopkeeper's money and cannot be dropped and "
            "rebuilt."))
    f.append(PageBreak())

    f += decision(
        5, "Confidence is scored per field, not per entry",
        context=(
            "The engine needs to decide when to ask the user something. The "
            "simple approach is one confidence score per extracted entry."),
        options=(
            "(a) a single blended score with one threshold; (b) a score per "
            "field, routing per field."),
        chosen=(
            "(b) — above 0.85 auto-fill, 0.50–0.85 ask about that specific "
            "field, below 0.50 fall back to buttons."),
        reasoning=(
            "'The amount is certain but the item is a guess' is the common "
            "case, not the exception. Blended into one number that entry "
            "lands mid-range and the app asks about everything — which is "
            "precisely the form-filling that kills the incumbents "
            "(section&nbsp;2). Per field, the app fills in the amount and "
            "asks one question about the item. That difference is the "
            "difference between a product used daily and one abandoned in a "
            "week."),
        tradeoff=(
            "More complex scoring and a UI that has to express uncertainty "
            "without looking broken. The confirm sheet solves this by tinting "
            "only the weak field and giving its reason in plain language."))

    f += decision(
        6, "Deduplication is a content hash, never a model",
        context=(
            "Bulk import can re-read messages already processed. Something "
            "must decide what is new. Since a model is already in the "
            "pipeline, asking it 'have I seen this before?' is tempting."),
        options=(
            "(a) ask the model; (b) a per-chat cursor holding a timestamp and "
            "a rolling window of SHA-1 content hashes."),
        chosen="(b).",
        reasoning=(
            "A model that is <b>99% accurate</b> at that question still "
            "double-counts one message in a hundred. At 30 messages a day "
            "that is a corrupted khata every few days, and — critically — the "
            "shopkeeper has no way to identify which entry is the wrong one. "
            "The error is silent and compounding. A hash comparison is exact, "
            "free, works offline, and survives re-importing an overlapping "
            "export. The model's job is the genuinely linguistic question: "
            "which of the new messages is an order?"),
        tradeoff=(
            "Effectively none. This is the clearest case in the project of "
            "choosing the boring mechanism because the failure mode of the "
            "clever one is unacceptable."))

    f += decision(
        7, "The voice model never sees a number",
        context=(
            "The dashboard reads the day's summary aloud in Hindi. The "
            "obvious implementation hands the figures to a model and asks for "
            "a natural sentence."),
        options=(
            "(a) model generates the sentence from the figures; (b) the "
            "database computes, a per-language template formats, and the "
            "voice model only reads a finished sentence."),
        chosen="(b).",
        reasoning=(
            "A model that renders <b>₹4,240</b> as 'about four thousand' has "
            "misstated a shopkeeper's own takings — and because he is "
            "listening rather than reading, he has no way to catch it. The "
            "template approach removes the opportunity entirely rather than "
            "trying to prompt around it. Amounts are spelled into words by "
            "the same number table the parser uses, run backwards, so the two "
            "directions cannot drift apart."),
        tradeoff=(
            "Each supported language needs a hand-written template, which is "
            "why we ship three properly rather than eleven approximately. The "
            "Marathi template is explicitly marked as a draft pending review "
            "by a Marathi speaker (section&nbsp;16)."))

    f += decision(
        8, "A zero-build dashboard, against our own plan",
        context=(
            "The project plan specified Next.js and a charting library for "
            "the web dashboard."),
        options=(
            "(a) Next.js as planned; (b) a single HTML file with a "
            "hand-drawn SVG chart and no build step."),
        chosen="(b).",
        reasoning=(
            "The team is new to this stack and the dashboard is demo "
            "infrastructure, not the product. A broken <font face='Courier'>"
            "npm run build</font> twenty minutes before a viva costs more "
            "than a plainer chart does. Nothing server-side changes if it is "
            "ported later, so the decision is cheap to reverse."),
        tradeoff=(
            "A deliberate departure from an approved plan, recorded here "
            "rather than quietly made. Charting is hand-rolled, so richer "
            "visualisations would mean more work than a library would need."))

    f += decision(
        9, "Plain Android Views for the overlay, not Compose",
        context=(
            "The rest of the Android ecosystem has moved to Jetpack Compose, "
            "and using it would be the conventional choice."),
        options="(a) Compose throughout; (b) plain Views for overlay windows.",
        chosen="(b) for everything drawn in a WindowManager overlay.",
        reasoning=(
            "Compose inside an overlay window requires "
            "ViewTreeLifecycleOwner and SavedStateRegistryOwner to be wired "
            "by hand, because there is no Activity providing them. That is "
            "real complexity and a real source of subtle bugs, and it buys "
            "nothing for a 54dp circle with a letter in it. The highest-risk "
            "component in the project is the overlay itself; keeping it "
            "boring was worth more than keeping it modern."),
        tradeoff=(
            "More verbose UI code, and the eventual in-app screens will "
            "likely use Compose, leaving two UI idioms in one codebase."))
    f.append(PageBreak())
    return f
