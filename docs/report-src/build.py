"""Assemble the VaaniLedger technical report."""

import sys
from reportlab.lib.units import mm
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate

from style import GREY_L, INK, PAGE, MARGIN, RULE, styles
import part1, part2, part3

S = styles()
OUT = sys.argv[1] if len(sys.argv) > 1 else "VaaniLedger-Technical-Report.pdf"


def furniture(canvas, doc):
    """Running header and footer. Page 1 is the cover and gets neither."""
    canvas.saveState()
    if doc.page > 1:
        canvas.setFont("Body", 7.5)
        canvas.setFillColor(GREY_L)
        canvas.drawString(MARGIN, PAGE[1] - 12 * mm, "VaaniLedger · Technical report")
        canvas.drawRightString(PAGE[0] - MARGIN, PAGE[1] - 12 * mm, "29 August 2026")
        canvas.setStrokeColor(RULE)
        canvas.setLineWidth(0.4)
        canvas.line(MARGIN, PAGE[1] - 14 * mm, PAGE[0] - MARGIN, PAGE[1] - 14 * mm)

        canvas.setFont("Body", 8)
        canvas.setFillColor(INK)
        canvas.drawCentredString(PAGE[0] / 2, 12 * mm, str(doc.page))
    canvas.restoreState()


doc = BaseDocTemplate(
    OUT, pagesize=PAGE,
    leftMargin=MARGIN, rightMargin=MARGIN,
    topMargin=MARGIN, bottomMargin=18 * mm,
    title="VaaniLedger — Technical Report",
    author="VaaniLedger team",
    subject="Architecture, decisions and the reasoning behind them",
)
frame = Frame(MARGIN, 18 * mm, PAGE[0] - 2 * MARGIN,
              PAGE[1] - MARGIN - 18 * mm, id="body", showBoundary=0)
doc.addPageTemplates([PageTemplate(id="main", frames=[frame], onPage=furniture)])

story = []
story += part1.front_matter()
story += part1.part_one()
story += part1.decision_log()
story += part2.architecture()
story += part3.measurement()
story += part3.defects()
story += part3.commercial()
story += part3.state_and_roadmap()

doc.build(story)
print(f"wrote {OUT}")
