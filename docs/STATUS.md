# VaaniLedger — status and road to deployable

Written 27 Aug 2026. Update it when the picture changes; a status doc that
has drifted is worse than none.

---

## The one-line version

The backend is finished and measured. The Android app builds, the floater
works over other apps, and capture reads real chat messages correctly — but
**nothing the app captures is saved yet.** Room, sync and reminders are
written, compiled, and never called.

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

---

## Written but never executed

Compiles, is covered by no runtime path, and must not be described as working.

- **`ReminderLinks` is never called.**
- *(The panel's hardcoded ₹4,240 was removed on 28 Aug — it now reads today's
  totals and the pending-sync count from Room.)*
- `ScreenCaptureService` and `MessageNotificationListener` are stubs.
- `Badlo` on the confirm sheet dismisses; there is no edit screen yet.

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

- [ ] **Remove `com.google.android.apps.messaging` from
      `accessibility_config.xml`.** It is there so the parser could be proven
      against a real view tree. Shipping it means the app can read a chat app
      it has no business reading.
- [ ] Move `API_BASE` off `10.0.2.2` (emulator-only) to the real host.
- [ ] Turn off the debug tree dump — it logs chat contents.

---

## Two things that cost time, so they are written down

**A build log you filter is a build log you cannot trust.** Piping gradle
through `grep '^e:'` hid a failure; the install then reported "Success"
having deployed a 20-minute-old APK, and the next twenty minutes went on
debugging code that was not running. Look at a build fail once before you
filter it.

**`sort` is not a version sort.** `sort | tail -1` picked `gradle-8.2` over
`gradle-8.13` because `'2' > '1'`. Use `sort -V`.
