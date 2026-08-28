# Technical report source

Regenerate the PDF:

```bash
cd docs/report-src
../../backend/.venv/bin/python build.py ../VaaniLedger-Technical-Report.pdf
```

Needs `reportlab` (in the backend venv). Structure:

| File | Contents |
|---|---|
| `style.py` | Fonts, palette, paragraph styles |
| `blocks.py` | Boxes, tables, decision records, defect cards |
| `part1.py` | Front matter, the problem, the decision log |
| `part2.py` | Architecture and the engine |
| `part3.py` | Measurement, defects, commercial analysis, roadmap |

## Two font traps, so they are not rediscovered

**Arial Unicode has no rupee sign.** U+20B9 was added to Unicode in 2010 and
this font predates it, so `₹` renders as a tofu box. `blocks.Paragraph` draws
that one glyph from SFNS instead.

**`stringWidth()` cannot test glyph coverage** — it returns a plausible width
for a *missing* glyph. The tofu was found only by rendering the pages and
looking at them, which is why visual QA is not optional here.

**Kohinoor also has the rupee sign but ships PostScript (CFF) outlines**,
which ReportLab cannot embed. It raises rather than degrading, so if you
switch fonts, check for a `glyf` table.
