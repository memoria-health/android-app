# BACKLOG.md — the running list

> One place for everything outstanding, ordered by what blocks what. Kept current
> as work lands. `FINDINGS.md` holds the reasoning behind each defect; this file
> holds the queue.
>
> Established 2026-08-03 at Aryaman's request ("keep making a note of our
> backlog"), because the queue had spread across FINDINGS, REQUIREMENTS,
> QUESTIONS and a session todo list, and no single document answered "what is
> left".

**Current directive (2026-08-03):** *"I want to make sure the core app features
and functionalities are in-sync with the most updated version of iOS."* That
settles the pin question — the port re-syncs to iOS `main` rather than holding at
`a6c27f6`. Cosmetic work is deprioritised behind behaviour and wire contract.

---

## A. iOS re-sync — 31 commits since the pin

Pin was `a6c27f6`. iOS `main` is now `2a84e0a`. 21 files, +698 −128.

Ordered by dependency: models and contract first, because everything below them
decodes through them.

| # | Item | iOS commit | Size | Status |
|---|---|---|---|---|
| A1 | **Tolerant decode: Patient, Detector** — required fields that a slightly older backend does not send kill **sign-in**, not one screen | `954dd14`, `0d68af6` | S | **DONE** — F-43, 8 tests |
| A2 | Sign-in no longer blocks on Realtime channel subscribes | `7842202` | S | **ALREADY DONE** — verified: `openChannelsDetached()` is `scope.launch`, poll starts alongside, and the comment cites the same 2026-07-28 incident |
| A3 | `NemaraAPIClient` +93 — home-briefing / home-report / presence endpoints | `5a11ae7`, `cac2833`, `9ce516a` | M | **NEXT**, together with A7 — endpoints with no consumer would be another built-but-unreachable surface |
| A4 | Wandering `whereRooms`: the detector picks which cameras it watches (the sheet that SETS the rooms) | `41a7efa` | M | **DONE** — 6 tests |
| A5 | Exit-door setup appears **only** on cameras the wandering detector watches | `e95c19f` | S | **DONE** — 9 tests |
| A6 | Wandering room match case-insensitive; duplicate room names blocked at creation | `6065961` | S | **DONE** — 8 tests. `whereScope` fallback deliberately NOT ported, see below |
| A7 | **`HomeStatusView` (+347)** — AI briefing, per-person verdict fold, triage-sorted roster, streaks, 3-mode presence | `5a11ae7` … `c934fcd` | **L** | not started. **Diff pin→HEAD, do not walk the commits**: this span rewrites itself repeatedly (`1454ed8` removed Recent Moments, `fa1c7af`/`e081e46` reworked the blind card, `86d43bc`/`c617ef4`/`7003a95` each restyled the previous one). Walking it would mean building four designs that were discarded. |
| A8 | Events: 12-hour AM/PM times, weekday dropped, SAFETY/ROUTINE/CUSTOM pills removed | `9d12c07`, `daf1063` | S | not started |
| A9 | Declutter passes 1–3: `InfoDot` component, eyebrows and captions removed across sensors / events / settings | `18123ac`, `07c901b`, `d031a09`, `566153c` | M | not started |

**Port the final state, not the commit series.** `6065961` added a `whereScope`
fallback to the room match; `954dd14` then removed `whereScope` entirely, and
`DetectorResponse` on the backend does not carry it. Porting commit-by-commit
would have added a read of a field that never arrives. Checked the backend
schema rather than trusting the commit — worth repeating for A7, which spans a
dozen commits that revise each other.

**A1 is not a port, it is a live defect on Android.** Verified in
`PatientModels.kt` and `DeviceModels.kt`: `camerasPaused`, `emailAlertsEnabled`,
`emailAlertRecipients`, `dataCollectionEnabled`, `alertTrigger` and `isSustained`
are all declared **without defaults**, so kotlinx-serialization treats them as
required. Against prod — which is an older backend than the local stack — the
Android app fails the same way iOS did: `bootstrap()` throws inside sign-in and
the user sees a decode error, not a broken screen. See FINDINGS.md F-43.

---

## B. Needs a decision from Aryaman

Nothing below is blocked on engineering time.

| # | Item | Where |
|---|---|---|
| B1 | **FCM send path.** The backend is APNs-only end to end — 18 APNs references, zero FCM. An Android token registered today is POSTed to Apple, rejected, and deleted on the 410. `google-services.json` alone does not turn push on. | F-39, REQ-7/8 |
| B2 | **Off-machine backup.** 60-odd commits exist in exactly one place, in a cloud-synced directory this workspace has already seen corrupt git operations. Needs a remote and a push; D-007 says I do not push without asking. | REQ-20 |
| B3 | **Privacy notice is iOS-only in six clauses** and Android now ships it: defines the Service as "iOS caregiver app", names Apple and not Google as push processor, claims "iOS Keychain" token storage. Also: Android has no Keystore-backed storage at all — verified, no `EncryptedSharedPreferences` or `MasterKey` in the source. Two decisions: re-export the notice, and/or raise Android storage to the claim. | F-42 |
| B4 | Analytics buffer outlives sign-out on both platforms; consent record has no version. | F-37, F-17 |
| B5 | Real Veritas legal documents — placeholders ship today. Same conversation as B3 and B4. | REQ-10 / Q4 |
| B6 | Sensor-setup step 2 interaction; enrollment quality metric replacing Vision's `faceCaptureQuality`. | Q5, Q2 / REQ-17, REQ-18 |

---

## C. Blocked on hardware

Cannot be closed on this machine at all.

REQ-1 blur performance · REQ-2 real cameras · REQ-3 sensor Wi-Fi ·
REQ-4 enrollment calibration · REQ-5 talk audio · REQ-6 battery and thermals ·
REQ-12 yaw-sweep clip · REQ-13 synthetic LiveKit publisher · F-3 luma range.

**The Live tab and the door picker are wired and unit-tested, not proven.** Video
actually arriving needs REQ-2 or REQ-13.

---

## D. Open defects, small

| # | Item | Where |
|---|---|---|
| D1 | Duplicate status bar at the bottom of the support screen; cause not identified | F-20 |
| D2 | Storage URLs cannot serve emulator and real device from one value (environment, not an app defect) | F-21 |
| D3 | `NewPatientHostTest > a failed create says so and stays on the form` is flaky; passes in isolation | F-36 |
| D4 | Five parity screens still uncompared — captures are on disk | — |

---

## E. Not started, deprioritised

Welcome tour / `TutorialOverlay`. Known absent, deliberately.

---

## Done recently

- **F-40** Live tab wired — 5,791 lines stopped being unreachable (`56699ac`)
- **F-41** Exit-door picker reachable; **zero unreachable screens** (`821255d`)
- **F-14** Privacy notice ported verbatim, digest-locked (`1861d19`)
- **F-38** Lock-screen body is the patient name, not the narrative (backend `3b9c52f`, on `main`)
- **F-26b** Push client — everything short of a Firebase project
