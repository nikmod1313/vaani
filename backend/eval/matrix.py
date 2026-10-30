"""
The known-answer matrix: 33,804 cases built from every combination below.

    cd backend && python3 -m eval.matrix

  1. count words (ek..das, digits, Devanagari) x amount forms x items x
     sentence shapes -- the count must be the quantity, never the bill
  2. a payment after a quiet spell, x wording x gap x figure x reminder
  3. a rate quoted per kilo in a chat, then an order in kilos
  4. photo / voice / document labels anywhere in a message
  5. items typed by hand ("do kurti")

Every case has its answer written down here, so a change that moves any of
them fails loudly. Exits non-zero on any failure.
"""
import itertools, json, sys
from collections import Counter
from datetime import datetime, timedelta
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
from app.extraction.conversation import Turn, resolve_all
from app.extraction.pipeline import extract
from app.extraction.rules import normalise_label

fails, passed, per = [], 0, Counter()
def check(group, name, got, want):
    global passed
    per[group] += 1
    if got == want: passed += 1
    else: fails.append((group, name, got, want))

# 1. single messages ---------------------------------------------------------
counts = {"ek": 1, "do": 2, "teen": 3, "chaar": 4, "paanch": 5, "chhe": 6, "saat": 7, "aath": 8, "nau": 9, "das": 10,
          "1": 1, "2": 2, "3": 3, "12": 12, "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5}
amounts = {"do hazar": 2000, "₹2,000": 2000, "2000": 2000, "rs 1,250": 1250, "1,25,000": 125000, "paanch sau": 500,
           "dhai sau": 250, "₹500/-": 500, "500 rupaye": 500, "500rs": 500, "दो हजार": 2000, "डेढ़ सौ": 150, "Rs.750": 750}
items = {"apparel": ["kurti", "saree", "shirt", "jeans", "dupatta", "top"], "kirana": ["chawal", "biscuit", "sabun", "anda", "maggi", "bread"]}
frames = ["{n} {i} {a}", "{n} {i} {a} udhaar", "Shyam ne {n} {i} kharide {a} mein", "{n} {i} kharidi, {a} udhaar",
          "{n} {i} chahiye {a} cash", "{n} {i} bhej do {a} upi se", "{a} ke {n} {i}", "{n} {i} liye {a} khaate mein likh do",
          "bhaiya {n} {i} dena, {a} gpay kar diya", "{n} {i} {a} ka udhaar"]
for cat, its in items.items():
    for (nw, nv), (aw, av), i, f in itertools.product(counts.items(), amounts.items(), its, frames):
        text = f.format(n=nw, i=i, a=aw)
        r = extract(text, category=cat).to_dict()
        check("1-message", text, (r["amount"], [l["quantity"] for l in r["line_items"]]), (float(av), [float(nv)]))

# 2. late payments -------------------------------------------------------------
T0 = datetime(2026, 10, 30, 10, 0)
def turn(minute, body, shop=False):
    return Turn(sender="Shop" if shop else "Rahul", body=body, timestamp=T0 + timedelta(minutes=minute),
                is_shopkeeper=shop, content_hash=f"h{minute}-{body}")
wordings = {  # message -> instrument it names ("" = none, "sent" = money sent, so online)
    "Payment done.": "", "payment kar diya": "", "maine payment kar di": "", "paid": "", "i have paid": "",
    "payment ho gaya bhaiya": "", "भुगतान कर दिया": "", "UPI kar diya.": "upi", "gpay kar diya": "upi",
    "phonepe se bhej diya": "upi", "online kar diya": "upi", "transfer kar diya": "upi", "Cash de diya.": "cash",
    "nakad de diye": "cash", "Paise bhej diye.": "sent", "paisa bhej diya hai": "sent", "पैसे भेज दिए": "sent",
    "paise de diye": "", "paytm kar diya hai": "upi", "i have completed the payment": "", "payment done ✅": "", "done payment": "", "jama kar diya": "", "gpay se bhej diya": "upi", "cash diya": "cash",
}
amount_forms = {"none": None, "exact": 2000.0, "part": 500.0, "over": 2500.0}
gaps = [50, 90, 180, 60 * 24, 60 * 24 * 6, 60 * 24 * 8]
for (w, named), (af, paid), gap, order_mode, link in itertools.product(
        wordings.items(), amount_forms.items(), gaps, ["udhaar", "unknown"], [False, True]):
    body = w if paid is None else f"{int(paid)} {w}"
    chat = [turn(0, "2 kurti chahiye"), turn(1, "2000 hua total", True)]
    if order_mode == "udhaar": chat.append(turn(2, "udhaar likh lo"))
    chat.append(turn(gap, body))
    links = [T0 + timedelta(minutes=gap - 5)] if link else []
    got = [(o.direction, o.amount, o.mode) for o in resolve_all(chat, "Rahul", "apparel", pay_link_sent=links)]
    if gap > 60 * 24 * 7 or (paid is not None and paid != 2000.0):
        want = [("sale", 2000.0, order_mode)]
        mode = named if named in ("upi", "cash") else ("upi" if link else "unknown")
        want.append(("payment", paid, mode))
    else:
        mode = named if named in ("upi", "cash") else ("upi" if named == "sent" or link else "cash")
        want = [("sale", 2000.0, mode)]
    check("2-late-payment", f"{body!r} after {gap}m order={order_mode} link={link}", got, want)

# 3. quoted rate chats (the screenshot shape) -------------------------------------
vegs = {"spring onions": ("spring", "onion"), "pyaz": (None, "pyaz"), "tamatar": (None, "tamatar"),
        "aloo": (None, "aloo"), "shimla mirch": ("shimla", "mirch"), "hari mirch": ("hari", "mirch"),
        "onion": (None, "onion"), "tomatoes": (None, "tomato"), "potatoes": (None, "potato"), "green chilli": ("green", "chilli")}
quotes = {"1kg ka 200rs": 200, "1 kg ka 200 rs": 200, "200 rs kilo": 200, "200 per kg": 200, "₹200/kg": 200,
          "200 ka 1 kilo": 200, "1kg 200": 200, "200 rupaye kilo": 200, "ek kilo ka do sau": 200, "200/kg": 200, "Rs 200 per kilo": 200, "200rs/kg": 200}
orders = {"meko 2kg chaiye": 2, "2 kilo de do": 2, "mujhe 3 kg chahiye": 3, "2kg bhej dena": 2, "do kilo chahiye": 2,
          "teen kilo bhej do": 3, "I want 2 kg": 2, "2 kg dedo": 2}
for (veg, (mod, name)), (q, rate), (o, kg) in itertools.product(vegs.items(), quotes.items(), orders.items()):
    chat = [turn(0, "hello bhaiya"), turn(1, f"{veg} hai kya?"), turn(1, "ha", True), turn(1, q, True), turn(2, o), turn(2, "Ok", True)]
    res = resolve_all(chat, "Isha", "kirana")
    got = [(x.direction, x.amount, [(i.name, i.quantity, i.unit) for i in x.items]) for x in res]
    check("3-quoted-rate", f"{veg} | {q} | {o}", got, [("sale", float(rate * kg), [(name, float(kg), "kg")])])

# 4. media in every position ------------------------------------------------------
media = ["IMG-20261030-WA0004.jpg", "245 kB", "1.2 MB", "PDF • 2 pages • 1.2 MB", "<Media omitted>", "‎image omitted",
         "VID-20261030-WA0001.mp4 (file attached)", "PTT-20261030-WA0002.opus", "bill (2).pdf", "STK-20261030-WA0003.webp",
         "‎sticker omitted", "document omitted", "Invoice_Oct.pdf • 3 pages", "AUD-20261030-WA0001.m4a 56 kB",
         "IMG_4521.HEIC", "Screenshot_2026-10-30-11-16-05.png", "0.9 MB", "12 kB", "VID-20261030-WA0007.mp4 3.4 MB"]
morders = [("2 kurti 1000 udhaar", 1000, 2), ("teen saree paanch hazar", 5000, 3), ("do kurti do hazar", 2000, 2),
           ("1 shirt 450 cash", 450, 1), ("4 kurti 300 each", 1200, 4)]
for m in media:
    r = extract(m, category="apparel")
    check("4-media", f"{m!r} alone", (r.amount, r.items), (None, []))
    for (order, amt, q), place in itertools.product(morders, ["{m} {o}", "{o} {m}", "{m}\n{o}", "{o}\n{m}", "{m}, {o}"]):
        text = place.format(m=m, o=order)
        r = extract(text, category="apparel").to_dict()
        check("4-media", repr(text), (r["amount"], [l["quantity"] for l in r["line_items"]]), (float(amt), [float(q)]))

# 5. typed labels -------------------------------------------------------------------
lab_items = ["kurti", "saree", "chawal", "blue kurti", "toor dal", "कुर्ती"]
for (nw, nv), it in itertools.product(counts.items(), lab_items):
    shown = int(nv)
    check("5-labels", f"{nw} {it}", normalise_label(f"{nw} {it}"), f"{it} {shown}")
    check("5-labels", f"{it} {shown}", normalise_label(f"{it} {shown}"), f"{it} {shown}")
for unit, short in [("kg", "kg"), ("kilo", "kg"), ("packet", "packet"), ("litre", "l")]:
    for (nw, nv) in counts.items():
        check("5-labels", f"{nw} {unit} chawal", normalise_label(f"{nw} {unit} chawal"), f"chawal {int(nv)}{short}")
for keep in ["5 star", "dhai sau", "do", "kurti", "7 up", "3 roses"]:
    check("5-labels", keep, normalise_label(keep), keep)

total = passed + len(fails)
print(json.dumps({"total": total, "passed": passed, "failed": len(fails)}))
print("per group:", dict(per))
byg = Counter(g for g, *_ in fails); print("fails per group:", dict(byg))
shown = Counter()
for g, name, got, want in fails:
    if shown[g] >= 12: continue
    shown[g] += 1; print(f"FAIL [{g}] {name}\n      got  {got}\n      want {want}")

sys.exit(1 if fails else 0)
