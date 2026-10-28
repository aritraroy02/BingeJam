# BingeJam: legal checklist (India)

Prepared 2026-10-29 for Aritraditya Roy, who develops and operates BingeJam as an individual.
This is a practical checklist, not legal advice. For the trademark, a registered trademark attorney
costs about ₹2,000–10,000 per class and is worth it if anyone objects to the application.

## Status

| Item | Status | Who |
|---|---|---|
| Name **BingeJam**: conflict check | Done (see below). The official IP India search still needs to be run. | Claude done; **you**: the official search |
| Trademark application (Form TM-A) | Ready to file (pack below) | **You** (needs your identity, OTP and payment) |
| Privacy Policy (DPDP Act 2023, IT Act) | Live: https://syncit-watchparty.web.app/privacy | Done |
| Terms of Service (IT Rules 2021 rule 3 content rules, 18+) | Live: https://syncit-watchparty.web.app/terms | Done |
| Grievance Officer published (IT Rules 2021) | Aritraditya Roy, aritraditya.roy@gmail.com, on both pages | Done. **You**: answer complaints within 24 h / 15 days |
| Account deletion in the app + web page (Play / Indus / DPDP right to erasure) | In the app from v0.5.0; page: https://syncit-watchparty.web.app/delete-account | Done |
| Minimum age 18 (DPDP: under-18s need verifiable parental consent) | In the Terms, Privacy Policy and sign-in screen | Done |
| Not affiliated with streaming services; no third-party logos | In the Terms, Privacy Policy, Settings and store listing | Done. Keep the store screenshots logo-free |
| Domain names | bingejam.com / .in / .app / .co / .io showed no DNS on 2026-10-29, so they're likely free | **You**: optional, buy at least bingejam.com and bingejam.in (about ₹800–1,500 a year each) |
| Store developer account (Indus) | Not started | **You**: PAN plus mobile OTP |
| Copyright in the code | Automatic under the Copyright Act, 1957; registration is optional | Nothing to do |
| GST / company | Not needed while the app is free and you publish as an individual | Revisit before charging money |

## 1. Name check (2026-10-29)

- **Exact "BingeJam" / "Binge Jam"**: no app, website or trademark found in web, Justia (US) or
  Play Store searches. bingejam.com, .in, .app, .co and .io didn't resolve, and the Play package
  `com.bingejam.app` returned 404.
- **Close marks to be aware of** (none is identical):
  - "BINGE" alone: Foxtel's Australian streaming service; several US "BINGE" marks for music
    entertainment; abandoned or unrelated Indian "BINGE" filings in classes 3, 10, 25 and 35.
  - "Jam": Spotify's group-listening feature name. Spotify has faced a lawsuit over it, but no
    "JAM" registration of theirs was found.
  - Combined words are judged as a whole. "BingeJam" is a coined combination, which helps, but an
    examiner may raise the earlier BINGE marks. Answer that with "the mark is a combination,
    distinct as a whole" (an attorney can draft this).
- **Still to do (you, about 10 minutes)**: run the official search at
  https://tmrsearch.ipindia.gov.in/tmrpublicsearch/ (it has a captcha, so Claude can't run it).
  Use "Wordmark", match type "Contains", for `BINGEJAM`, `BINGE JAM` and `BINGE`, in classes 9, 38,
  41 and 42. Use "Phonetic" for `BINJAM` and `BINGJAM`. If an identical or near-identical live mark
  appears in those classes, stop and tell Claude.

## 2. Trademark filing pack (Form TM-A, e-filing)

**Where:** https://ipindiaonline.gov.in/trademarkefiling/ → register a user ID (email + mobile
OTP) → "New Application" → **TM-A**.

| Field | What to enter |
|---|---|
| Applicant | Aritraditya Roy, **Individual**, nationality Indian, your full postal address |
| Trade description | "Proposed to be used" (no user affidavit needed) |
| Mark type | **Word** |
| Mark | `BINGEJAM` (word marks are filed in capitals and protect every style). Add a logo application later once there's a final logo |
| Language | English |
| Address for service | Your address in India, and the email you check |
| Signature | Sign the e-form with OTP (Aadhaar e-sign) or a DSC, whichever the portal offers you |

**Classes and specifications** (paste as-is; you can drop a class to save money):

- **Class 9 (essential):** Downloadable mobile application software for synchronising media playback
  between users' devices, for social networking, instant messaging and sharing information about
  media being watched or listened to.
- **Class 42 (essential):** Software as a service (SaaS) and providing temporary use of
  non-downloadable software for synchronising media playback between users, social networking and
  messaging; hosting of online platforms for group watching and listening sessions.
- **Class 38 (recommended):** Providing online chat rooms and electronic messaging services;
  transmission of messages and notifications between users of a mobile application.
- **Class 41 (optional):** Entertainment services, namely organising and providing online social
  watch-party and group-listening sessions; providing information about films, series and music
  being watched or listened to by users.

**Government fee** (individual, online): **₹4,500 per class**. That's ₹9,000 for 9 + 42, ₹13,500
with 38 and ₹18,000 for all four. Confirm on the portal's fee screen; individuals, startups and
MSMEs pay this lower rate.

**After filing**
1. You get an **application number** at once. From then on you can show **BingeJam™**. Don't use
   ® until it's registered.
2. **Examination report** in about 1–3 months. If there's an objection, you must **reply within 30
   days** (an attorney helps here).
3. The mark is advertised in the **Trade Marks Journal**, followed by a **4-month opposition period**.
4. If there's no opposition: a **registration certificate**, valid for 10 years, renewable. You can
   then use **BingeJam®**.
5. Track status at https://tmrsearch.ipindia.gov.in/eregister/ with the application number.

## 3. Ongoing obligations

- **Grievances:** acknowledge within 24 hours and resolve within 15 days. Act on lawful takedown
  requests within the IT Rules timelines. Keep a simple log (date, user, issue, action).
- **Data requests:** access, correction and deletion requests by email get handled within the times
  above. The in-app deletion covers most cases. In-app deletion does **not** reach room chat
  messages/reactions, older room memberships or old hosted previews (no per-user index). For an
  emailed request, ask Claude to remove them with an admin scan of `chats/`, `reactions/`,
  `rooms/*/members/{uid}` and `previews/` (hostUid). A scheduled room cleanup would make this automatic.
- **Breach:** if user data leaks, inform affected users and the Data Protection Board of India
  promptly (DPDP Act s.8(6)).
- **DPDP Rules:** notified in November 2025 and phased in over about 18 months. The policy already
  covers notice, consent, rights, grievance, deletion and age. Re-check when the final provisions
  apply (around mid-2027).
- **Keep the policies in sync with the app.** Any new data collected (analytics, payments,
  location...) means updating `web/privacy.html` first. Store data-safety forms must match it.
- **Before charging money:** add refund and payment terms, consider GST registration and Udyam, and
  re-check consumer-law (E-Commerce Rules 2020) disclosures.
