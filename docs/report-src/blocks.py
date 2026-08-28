"""Reusable page furniture: boxes, tables, decision cards, code blocks."""

from reportlab.lib import colors
from reportlab.lib.units import mm
from reportlab.platypus import KeepTogether, Spacer, Table, TableStyle
from reportlab.platypus import Paragraph as _Paragraph

from style import (CUT, CUT_BG, GOOD, GOOD_BG, GREY_L, INDIGO, INDIGO_BG, INK,
                   RULE, SAFFRON, SAND, WARN, WARN_BG, styles)

S = styles()
CONTENT_W = 170 * mm

RUPEE = "\u20b9"


def rupee_safe(text):
    """Draw the rupee sign from Kohinoor; everything else stays Arial Unicode."""
    if RUPEE not in text:
        return text
    return text.replace(RUPEE, f'<font name="Rupee">{RUPEE}</font>')


def Paragraph(text, style, **kw):
    """Paragraph that renders the rupee sign instead of a tofu box."""
    return _Paragraph(rupee_safe(text), style, **kw)


def para(text, style="Doc"):
    return Paragraph(text, S[style])


def h1(text, kicker=None):
    out = []
    if kicker:
        out.append(Paragraph(kicker.upper(), S["Kicker"]))
    out.append(Paragraph(text, S["H1"]))
    out.append(Spacer(1, 3 * mm))
    return out


def h2(text):
    return Paragraph(text, S["H2"])


def h3(text):
    return Paragraph(text, S["H3"])


def bullets(items):
    return [Paragraph(f"•&nbsp;&nbsp;{i}", S["VBullet"]) for i in items]


def code(text):
    """Monospaced block on a tinted ground."""
    t = Table([[Paragraph(text.replace("\n", "<br/>").replace(" ", "&nbsp;"),
                          S["VCode"])]], colWidths=[CONTENT_W])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), SAND),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    return [t, Spacer(1, 4 * mm)]


TONES = {
    "indigo": (INDIGO_BG, INDIGO),
    "good": (GOOD_BG, GOOD),
    "warn": (WARN_BG, WARN),
    "cut": (CUT_BG, CUT),
}


def box(label, body, tone="indigo"):
    """A callout. Tinted ground rather than an edge stripe."""
    bg, fg = TONES[tone]
    inner = [Paragraph(label.upper(), S["BoxTitle"])]
    inner[0].textColor = fg
    st = S["BoxTitle"].clone("t")
    st.textColor = fg
    inner = [Paragraph(label.upper(), st)]
    for p in (body if isinstance(body, list) else [body]):
        inner.append(Paragraph(p, S["BoxBody"]))
    t = Table([[inner]], colWidths=[CONTENT_W])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), bg),
        ("LEFTPADDING", (0, 0), (-1, -1), 11),
        ("RIGHTPADDING", (0, 0), (-1, -1), 11),
        ("TOPPADDING", (0, 0), (-1, -1), 9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return [KeepTogether([t]), Spacer(1, 4 * mm)]


def table(header, rows, widths=None, align_right=()):
    """A plain data table: tinted header, hairline rules, no vertical lines."""
    widths = widths or [CONTENT_W / len(header)] * len(header)
    # A cell-level ALIGN does nothing when the cell holds a Paragraph — the
    # paragraph's own alignment wins. Right-aligned columns therefore need a
    # right-aligned STYLE, not a table style.
    from reportlab.lib.enums import TA_RIGHT
    right_cell = S["Cell"].clone("CellR"); right_cell.alignment = TA_RIGHT
    right_head = S["CellH"].clone("CellHR"); right_head.alignment = TA_RIGHT

    data = [[Paragraph(h, right_head if i in align_right else S["CellH"])
             for i, h in enumerate(header)]]
    for r in rows:
        data.append([
            Paragraph(str(c),
                      right_cell if i in align_right
                      else (S["CellB"] if i == 0 else S["Cell"]))
            for i, c in enumerate(r)
        ])
    t = Table(data, colWidths=widths, repeatRows=1)
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), SAND),
        ("LINEBELOW", (0, 0), (-1, 0), 0.6, colors.HexColor("#C5C1B8")),
        ("LINEBELOW", (0, 1), (-1, -2), 0.4, RULE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]
    for c in align_right:
        style.append(("ALIGN", (c, 0), (c, -1), "RIGHT"))
    t.setStyle(TableStyle(style))
    return [t, Spacer(1, 4.5 * mm)]


def decision(number, title, context, options, chosen, reasoning, tradeoff=None):
    """
    A numbered decision record.

    Deliberately forces four things onto the page for every decision: what
    was actually at stake, what the alternatives were, what was chosen, and
    what it cost. A decision log that records only the choice is a list of
    outcomes, not reasoning.
    """
    st = S["BoxTitle"].clone("dt")
    st.textColor = SAFFRON
    rows = [
        [Paragraph(f"DECISION {number}", st)],
        [Paragraph(title, S["H2"])],
        [Paragraph(f"<b>The situation.</b> {context}", S["BoxBody"])],
        [Paragraph(f"<b>Options considered.</b> {options}", S["BoxBody"])],
        [Paragraph(f"<b>Decision.</b> {chosen}", S["BoxBody"])],
        [Paragraph(f"<b>Business reasoning.</b> {reasoning}", S["BoxBody"])],
    ]
    if tradeoff:
        rows.append([Paragraph(f"<b>What it cost.</b> {tradeoff}", S["BoxBody"])])

    t = Table(rows, colWidths=[CONTENT_W])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.white),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 12),
        ("TOPPADDING", (0, 0), (0, 0), 10),
        ("BOTTOMPADDING", (0, -1), (-1, -1), 9),
        ("TOPPADDING", (0, 1), (-1, -1), 1),
        ("BOTTOMPADDING", (0, 0), (-1, -2), 3),
    ]))
    return [KeepTogether([t]), Spacer(1, 5 * mm)]


def bug(number, title, what, cost, lesson):
    st = S["BoxTitle"].clone("bt")
    st.textColor = CUT
    t = Table([
        [Paragraph(f"BUG {number:02d}", st)],
        [Paragraph(title, S["H3"])],
        [Paragraph(what, S["BoxBody"])],
        [Paragraph(f"<b>Real-world cost.</b> {cost}", S["BoxBody"])],
        [Paragraph(f"<b>Lesson.</b> {lesson}", S["BoxBody"])],
    ], colWidths=[CONTENT_W])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.white),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 11),
        ("RIGHTPADDING", (0, 0), (-1, -1), 11),
        ("TOPPADDING", (0, 0), (0, 0), 9),
        ("BOTTOMPADDING", (0, -1), (-1, -1), 8),
        ("TOPPADDING", (0, 1), (-1, -1), 1),
        ("BOTTOMPADDING", (0, 0), (-1, -2), 2),
    ]))
    return [KeepTogether([t]), Spacer(1, 4 * mm)]


def stat_row(items):
    """A row of headline figures."""
    cells = []
    for value, label in items:
        vs = S["H1"].clone("sv")
        vs.fontSize = 19
        vs.leading = 22
        vs.textColor = INDIGO
        cells.append([Paragraph(value, vs), Paragraph(label, S["Caption"])])
    t = Table([cells], colWidths=[CONTENT_W / len(items)] * len(items))
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (0, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LINEABOVE", (0, 0), (-1, 0), 0.6, RULE),
        ("LINEBELOW", (0, -1), (-1, -1), 0.6, RULE),
    ]))
    return [t, Spacer(1, 5 * mm)]
