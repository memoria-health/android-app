# FINDINGS.md — review findings awaiting adjudication

Findings raised by the per-phase code reviews that have **not** yet been either
fixed or dismissed. Distinct from the other ledgers:

| File | Holds |
|---|---|
| `HARDENING.md` | built and green, but thin — a known weakness with a named failure |
| `DIFFERENCES.md` | a divergence from iOS that has been decided and justified |
| `QUESTIONS.md` | blocked, waiting on an answer |
| **`FINDINGS.md`** | **a reviewer said this is wrong; nobody has ruled on it yet** |

An entry leaves this file in one of three ways: fixed (say where), moved to
`HARDENING.md` / `DIFFERENCES.md` (say which), or dismissed (say why). Never
delete a row.

---

## A note on what was lost, because it matters

The Phase 3–8 reviews produced roughly 40 Important and 60 Minor findings. They
were held in conversation context and **most were lost to a context compaction
before they were ever written down.** This file starts from the subset that
survived, each of which I have now **re-verified against the source** rather
than trusted from a summary — two were more serious than the summary said.

The remedy for the rest is not to try to recall them. It is a fresh review pass
against the tree, which is the honest way to regenerate that list and is
tracked as F-8. **The process failure — reviewing into a chat buffer instead of
into a file — is the finding that matters most here.**

---

## OPEN

### F-3 — Scene luma is measured on a different scale than iOS
**Phase 6 · Important · not yet re-verified**

Enrollment reads brightness from the YUV luma plane, which is studio-swing
(16–235) on most devices, where iOS applies a full-range Rec.601 weighting to
BGRA. The same room therefore reports a **higher** luma on Android, and the
`0.28` dark-luma floor that gates "too dark" guidance was calibrated against
the iOS scale.

**Failure guarded:** the too-dark warning fires late or not at all, so
enrollment accepts frames in light iOS would have rejected — silently degrading
the face gallery that everything downstream depends on.

Related to REQ-18 / Q2, but distinct: that is about the *quality* metric, this
is about *brightness*, which ports 1:1 on iOS and does not here.

---

### F-4 — `allFrames()` thread race
**Phase 6 · Important · not yet re-verified**

Reported as an unsynchronised read of the retained-frame collection while the
camera thread writes it. Needs confirmation against the current source before
it is acted on.

---

### F-5 — `EnrollmentCoverageSink` is never wired
**Phase 6 · Important · not yet re-verified**

Same shape as H-12 and D8: a real, tested component with no caller. Resolves
either as dead-by-design (delete it) or as part of the integration work — see
F-10, which is the same defect at the scale of the whole app.

---

### F-6 — Five `FaceCoverageModel` divergences with no `DIFFERENCES.md` entry
**Phase 6 · Minor–Important · not yet re-verified**

Q2 records that the model ports 1:1. Reviewers found five places where it does
not. Either they are justified and belong in `DIFFERENCES.md`, or they are
parity bugs. Undocumented divergence in the one file whose whole premise is
"this ports unchanged" is the problem regardless of which.

---

### F-7 — The render watchdog has no `DIFFERENCES.md` entry
**Phase 4 · Minor · verified as absent 2026-08-02**

`PARITY.md §12` and `QUESTIONS.md` Q3 both explicitly require the Android
watchdog mechanism to be recorded, because the intent was ported and the
mechanism re-derived. No entry exists.

---

### F-8 — Regenerate the lost findings
**Process · Important**

A fresh review pass over Phases 3, 5, 7 and 8 against the tree, reviewing into
this file rather than into conversation. Not a re-run of the old reviews — a
new one, since the code has moved.

---

### F-10 — 21 of 24 screens are unreachable; the app is parts, not a product
**All phases · Critical · verified 2026-08-02**

The only navigation graph is `AuthNavHost`: Welcome, Login, ForgotPassword.
Signing in lands on `SignedInPlaceholder`. Roster, live video, events, cameras,
detectors, settings, enrollment and onboarding are all built, all tested, and
all unreachable.

Behind it:

- **One ViewModel exists** (`AuthViewModel`). The other 21 screens are pure
  composables over state types with no producer — nothing fetches a roster,
  subscribes a Realtime channel, or mints a LiveKit token in response to a tap.
- `api.bootstrap()` and `api.teardown()` are still `TODO(phase-3)` in
  `AppEnvironment`, so sign-in reaches `POST /v1/me` and stops.
- `DeepLinkRouter` is written, tested, and referenced by nothing.

**Failure guarded:** "all eight phases have code" and "the app works" are very
different statements, and the test suite cannot tell them apart — 1466 tests
pass against an app whose every feature screen is dead code. Every parity defect
that only appears when a screen meets real data is still ahead of us, not
behind.

Not a defect in any one unit, which is why no per-phase review raised it. It is
the largest single piece of work remaining.

---

## RESOLVED

### F-1 — Two loudspeaker implementations, at two layers, with contradictory rules
**Phase 4 · Important · FIXED 2026-08-02**

Collapsed to one implementation, in `LiveKitClient.setMicrophoneEnabled`, which
every mic publish goes through. The surviving rule is the headset-aware one. The
decision is extracted to `data/livekit/AudioRouting.kt` as a pure function so it
stays testable behind a live `Room`, and pinned by `AudioRoutingTest` — the
headset case now has a test for the first time; neither original copy had one.
`TalkAudioRoute`, `AudioSwitchTalkAudioRoute` and `TalkController`'s `audioRoute`
parameter are deleted. Recorded as DECISIONS.md D-014.

---

### F-2 — Two copies of the LiveKit identity and room strings
**Phase 4 · Important · FIXED 2026-08-02**

`LiveKitTokens` deleted; `LiveKitIdentity` is the single definition and now also
carries the two role strings. Both lowercase calls take `Locale.ROOT`, pinned by
a test that runs under a Turkish default locale. The three duplicate tests in
`TalkModeTest` went away with the copy they asserted against.

---

### F-9 — Dead date formatters left behind by the codec collapse
**Phase 2 · FIXED 2026-08-02**

Deleting the forked `MiscInstantSerializer` left its five formatters
(`MICROSECOND_ISO8601`, `FRACTIONAL_ISO8601`, `PLAIN_ISO8601`, `DATE_ONLY`,
`OFFSET_PARSERS`) behind in `data/model/MiscModels.kt`, unreferenced. Private,
so they could not drift back into use, but stale code in the very commit whose
purpose was removing the fork. Deleted.
