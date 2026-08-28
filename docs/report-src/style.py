"""Shared styling for the VaaniLedger technical report."""

from reportlab.lib import colors
from reportlab.lib.enums import TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

# Arial Unicode is the only system face here that carries the rupee sign,
# Devanagari and the check marks together. ReportLab's built-in Helvetica has
# none of them and renders each as a solid black box.
AU = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
pdfmetrics.registerFont(TTFont("Body", AU))
pdfmetrics.registerFont(TTFont("BodyB", AU))

# Arial Unicode predates the Indian Rupee Sign (U+20B9, added to Unicode in
# 2010), so it renders as a tofu box. Nothing on this machine has BOTH the
# rupee and the Devanagari + check marks the report needs, so Kohinoor is
# registered solely to draw that one glyph; `blocks.rupee_safe` swaps it in.
#
# Note that a stringWidth() probe reports a non-zero width for a MISSING
# glyph, so it cannot be used to test coverage — this was found only by
# rendering the pages and looking at them.
# SFNS is the system UI face: it has the rupee sign AND TrueType outlines.
# Kohinoor also has the glyph but ships PostScript (CFF) outlines, which
# ReportLab cannot embed — it raises rather than silently degrading.
RUPEE_FONT = "/System/Library/Fonts/SFNS.ttf"
pdfmetrics.registerFont(TTFont("Rupee", RUPEE_FONT))
pdfmetrics.registerFontFamily("Body", normal="Body", bold="BodyB",
                              italic="Body", boldItalic="BodyB")
MONO = "Courier"

# The project's own palette: indigo as ledger ink, saffron for udhaar.
INK      = colors.HexColor("#17191E")
INDIGO   = colors.HexColor("#253A6E")
SAFFRON  = colors.HexColor("#C9752F")
GREY     = colors.HexColor("#5A6070")
GREY_L   = colors.HexColor("#8B91A0")
RULE     = colors.HexColor("#DFDCD5")
SAND     = colors.HexColor("#F7F6F3")
GOOD     = colors.HexColor("#2C6149")
GOOD_BG  = colors.HexColor("#E4EFE9")
WARN     = colors.HexColor("#8A5D0B")
WARN_BG  = colors.HexColor("#F7EEDC")
CUT      = colors.HexColor("#9B3A2C")
CUT_BG   = colors.HexColor("#F7E7E3")
INDIGO_BG = colors.HexColor("#E9EDF5")

PAGE = A4
MARGIN = 20 * mm

def styles():
    s = getSampleStyleSheet()
    def add(name, **kw):
        base = dict(fontName="Body", textColor=INK, leading=14.5)
        base.update(kw)
        s.add(ParagraphStyle(name=name, **base))

    add("Doc", fontSize=10.2, leading=15.6, alignment=TA_JUSTIFY,
        spaceAfter=7, textColor=colors.HexColor("#2B2F38"))
    add("Lead", fontSize=11.4, leading=17, alignment=TA_LEFT,
        spaceAfter=9, textColor=colors.HexColor("#3A404C"))
    add("H1", fontName="BodyB", fontSize=21, leading=25, spaceBefore=0,
        spaceAfter=3, textColor=INK)
    add("H2", fontName="BodyB", fontSize=14.5, leading=18, spaceBefore=15,
        spaceAfter=6, textColor=INK)
    add("H3", fontName="BodyB", fontSize=11.4, leading=15, spaceBefore=11,
        spaceAfter=4, textColor=INDIGO)
    add("Kicker", fontName="BodyB", fontSize=7.8, leading=10, spaceAfter=3,
        textColor=SAFFRON)
    add("VBullet", fontSize=10.2, leading=15, leftIndent=11, spaceAfter=4,
        bulletIndent=2, textColor=colors.HexColor("#2B2F38"))
    add("VCode", fontName=MONO, fontSize=8.1, leading=11.4,
        textColor=colors.HexColor("#2B2F38"))
    add("Caption", fontSize=8.4, leading=11.6, textColor=GREY_L, spaceAfter=4)
    add("CellH", fontName="BodyB", fontSize=7.6, leading=10, textColor=GREY)
    add("Cell", fontSize=9, leading=12.4, textColor=colors.HexColor("#2B2F38"))
    add("CellB", fontName="BodyB", fontSize=9, leading=12.4, textColor=INK)
    add("BoxTitle", fontName="BodyB", fontSize=7.8, leading=10, spaceAfter=3)
    add("BoxBody", fontSize=9.5, leading=14, spaceAfter=5,
        textColor=colors.HexColor("#2B2F38"))
    add("TitleBig", fontName="BodyB", fontSize=40, leading=42, textColor=INK)
    add("TitleSub", fontSize=13, leading=19, textColor=GREY)
    add("TOCItem", fontSize=9.8, leading=17, textColor=colors.HexColor("#2B2F38"))
    return s
