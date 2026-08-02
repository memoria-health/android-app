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
**Phase 6 · Important · CONFIRMED 2026-08-02 · needs a device to fix correctly**

Enrollment reads brightness from the YUV luma plane, which is studio-swing
(16–235) on most devices, where iOS applies a full-range Rec.601 weighting to
BGRA. The same room therefore reports a **higher** luma on Android, and the
`0.28` dark-luma floor that gates "too dark" guidance was calibrated against
the iOS scale.

**Failure guarded:** the too-dark warning fires late or not at all, so
enrollment accepts frames in light iOS would have rejected — silently degrading
the face gallery that everything downstream depends on.

Related to REQ-18 / Q2, but distinct: that is about the *quality* metric, this
is about *brightness*, which the port assumed transferred 1:1 and does not.

**The arithmetic.** A scene iOS scores at exactly the 0.28 dark floor is luma
71/255. The same scene on a studio-swing plane reads `16 + 0.28 x 219 = 77`, or
0.30 — above the floor. The warning fires late, or never.

**Verified in source.** `sceneLuma` in `ui/enrollment/EnrollmentFrameAnalysis.kt`
divides the raw plane value by 255. The comment above it addressed the Rec.601
*weighting* (which genuinely does transfer) and said nothing about *range*,
which is the half that does not — so the code read as checked when it was not.
That comment now states the gap.

**Deliberately not fixed blind.** `(y - 16) / 219` is right only if the plane is
studio-swing, and Android exposes no API for which range a device emits.
Applying it unconditionally trades this error for a comparable one on
full-range devices. Pointing a camera at a known target settles it in minutes.
Folded into REQ-4.

---

### F-4 — `allFrames()` thread race — DOWNGRADED, latent not live
**Phase 6 · Minor · verified 2026-08-02**

Not a live defect. `EnrollmentCamera` posts `ingest` and `noteFaceLost` to the
main executor (`mainExecutor.execute { ... }`), which is exactly the
single-thread confinement `FaceCoverageModel`'s threading note requires, and
`allFrames()` has no production caller at all — only tests.

It stays open because the contract is load-bearing and invisible: the upload
path that will call `allFrames()` has not been written, and if it reads from a
background thread while the analyzer feeds the model, the race the reviewer
described becomes real. The fix is to honour the confinement when that code
lands, not to add a lock now. Belongs with F-10.

---

### F-5 — `EnrollmentCoverageSink` has no implementation, so the camera cannot be built
**Phase 6 · Important · CONFIRMED and sharpened 2026-08-02**

More precise than reported. The sink is not "unwired" — `EnrollmentCamera`
takes one as a constructor parameter and calls it correctly. The problem is that
**nothing anywhere implements the interface**, in `main` or in test.
`FaceCoverageModel` has all three matching methods and does not declare the
interface, so the two halves were built to meet and never joined.

`EnrollmentCamera` therefore cannot be constructed, which means face enrollment
cannot run. Exactly the shape of H-12 (`PersonDetector`) and D8: every part real
and tested, the seam between them missing. The adapter is a few lines; that it
is absent is what F-10 is about.

---

### F-6 — Five `FaceCoverageModel` divergences with no `DIFFERENCES.md` entry
**Phase 6 · Minor–Important · not yet re-verified**

Q2 records that the model ports 1:1. Reviewers found five places where it does
not. Either they are justified and belong in `DIFFERENCES.md`, or they are
parity bugs. Undocumented divergence in the one file whose whole premise is
"this ports unchanged" is the problem regardless of which.

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

### F-7 — The render watchdog has no `DIFFERENCES.md` entry
**Phase 4 · Minor · FIXED 2026-08-02**

Written up as `DIFFERENCES.md` D14: the counting `VideoSink` that substitutes
for iOS's `VideoView.isRendering`, and the lifecycle scoping that substitutes
for a `Timer` that stops firing when the app suspends. The source comments were
already thorough — the entry was the missing part, and `PARITY.md` §12 requires
it because only the intent ports.

---

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
