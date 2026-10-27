# Vaani — status and road to deployable

Written 27 Aug 2026; corrected 27 Oct 2026, when most of what follows had
drifted. Update it when the picture changes; a status doc that has drifted
is worse than none.

---

## The one-line version

*(27 Oct.)* The whole loop runs on the phone and has been driven end to end
on an emulator: a customer's message arrives in a chat app → the bubble
reads it → the on-device engine resolves it → the confirm and edit sheets →
Room → the khata, the report and the reminder. The server is optional.
Conversation-level accuracy is measured on held-out WhatsApp threads (see
the README); what is still unmeasured is the Claude stage and real
shopkeeper messages.

---

## Verified working

Everything here has been run, not just written.

| Piece | Evidence |
|---|---|
| Extraction engine | 181 tests; macro F1 1.000 on 122 labelled cases |
| Hindi number words, both directions | `dhai sau` → 250; 4240 → "chaar hazaar do sau chalees" |
| WhatsApp export ingest | 10 messages → 8 blocks → 4 orders, 50% chatter dropped |
| Watermark dedup | Re-importing the same export yields 0 new |
| Ledger store | Sync replay reports `accepted=0 duplicates=1` |
| FIFO khata aging | Seeded shop spreads 14–63 days, not all-at-87 |
| Web dashboard | Verified in a browser: light, dark, mobile 375px, clean console |
| Spoken summary | 386 chars Hindi, amounts spelled out, under Bulbul's 2500 cap |
| **Floater** | Draws over the home screen and other apps; taps open the panel |
| **Chat capture** | Reads exactly 4 real messages from a live chat app, 0 chrome |
| **Capture → engine** | Those 4 strings → ₹250, udhaar, AUTO 0.89 |
| **Full loop on device** | Wiped DB → panel `₹0 · "Aaj koi entry nahi"` → capture → confirm → panel `₹250 sale / ₹250 udhaar · "1 entries · sab synced"`, matching the server |
| **Badlo → learning loop** | Edit sheet prefilled, customer typed in, `POST /corrections 200`, pair stored: `customer '' → 'Rahul Verma'` |
| **Khata** | `Kul baaki ₹250` · `Rahul Verma ₹250` · entries with source tags |
| **Reminder** | Draft composed and intent fired: *"Namaste Rahul Verma ji 🙏 Sharma Kirana Store se — aapka ₹250 baaki hai (28 Aug tak)…"* — targeted at `com.whatsapp`, falls back to the share sheet when it is absent |

---

## Written but never executed

Compiles, is covered by no runtime path, and must not be described as working.

- `ScreenCaptureService` (screenshot + OCR) and `MessageNotificationListener`
  (staging incoming messages) are still stubs.

*(Corrected 27 Oct: `ReminderLinks` is called from the khata sheet's
"Yaad dilao" and now carries a UPI pay link once the shop sets its UPI ID;
`Badlo` opens a real edit sheet, which also feeds the correction store.)*

*(Room, Retrofit and `SyncWorker` moved out of this list on 27 Aug — the core
loop now exercises all three.)*

---

## Not measured

Wired, credential-gated, and honestly unknown until someone runs them.

- **Stage 2 (Claude).** Needs `ANTHROPIC_API_KEY`. The rules-only baseline is
  the control group; the lift is unquantified.
- **Sarvam Bulbul TTS.** Needs `SARVAM_API_KEY`.
- **Cost per shop per month.** Could be ₹5 or ₹500.

---

## Tier 1 — the demo tells a complete story

What the capstone needs. Roughly two weeks for two people.

1. ~~Capture → `/extract` → confirm sheet → Room.~~ **Done 27 Aug.**
2. Khata list and customer detail screens; a real `Badlo` edit screen.
3. ~~Panel reads real numbers from Room.~~ **Done 28 Aug.**
4. Backend reachable from the phone — LAN IP, not localhost.
5. Enqueue `SyncWorker`; show the pending badge.
6. Reminder composer calling `ReminderLinks`.
7. **Run capture against real WhatsApp on the demo phone.**
8. Install on a real phone and leave it overnight (OEM battery killers).

**#7 is still the open risk.** Capture is proven against Google Messages —
same accessibility API, same class of view tree — but WhatsApp's hierarchy is
its own, and its ids are not a public contract. Budget a day. If it reads
nothing, `ChatScraper.dump()` tells you why in about a minute.

---

## Tier 2 — one real shopkeeper, one week

- **Authentication.** Today anyone who knows a `shop_id` can read and write
  that shop's ledger. Disqualifying the moment real money is in it.
- Deployed backend with HTTPS.
- **Room migrations, tested.** That database is his money.
- Backup and export — losing the phone must not lose the khata.
- **WhatsApp version drift.** A silent "read zero messages" needs to be
  detected and surfaced, not logged into the void.
- Crash reporting.
- Error states: permission revoked mid-use, no network, storage full, wrong
  clock.
- Real shopkeeper messages in the eval, held out.

---

## Tier 3 — deployable to many

- **The Play Store is not available to this app.** Accessibility-API apps are
  restricted to ones whose core function is assisting users with
  disabilities. Sideload or enterprise distribution. Say so in the report.
- Privacy policy, consent flow, and India's DPDP Act for customer names,
  numbers and debts.
- Multi-tenant backend: Postgres, migrations, monitoring.
- Onboarding with no developer present. Two Settings trips is already a lot.

---

## Before any build leaves the team

- [x] **Google Messages cannot ship.** It is now listed only in
      `src/debug/res/xml/accessibility_config.xml` and gated on
      `BuildConfig.DEBUG` in code; the release APK's config was checked with
      `aapt2` and reads WhatsApp and WhatsApp Business only.
- [ ] Move `API_BASE` off `10.0.2.2` (emulator-only) to the real host — or
      set it per phone in Settings → Server address.
- [x] The debug tree dump only runs when `BuildConfig.DEBUG`; release builds
      never log chat contents.
- [ ] Sign the release build (`assembleRelease` produces an unsigned APK).

---

## Two things that cost time, so they are written down

**A build log you filter is a build log you cannot trust.** Piping gradle
through `grep '^e:'` hid a failure; the install then reported "Success"
having deployed a 20-minute-old APK, and the next twenty minutes went on
debugging code that was not running. Look at a build fail once before you
filter it.

**`sort` is not a version sort.** `sort | tail -1` picked `gradle-8.2` over
`gradle-8.13` because `'2' > '1'`. Use `sort -V`.
