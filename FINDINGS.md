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

### F-13 — The main tab bar is text-only where iOS shows icon + label
**Integration · Minor · known at the time of writing**

`MainShell`'s `NavigationBar` renders each tab's title as the *icon* slot with
no label, so the bar reads as two text pills. iOS uses
`Label("Home", systemImage: "house.fill")` and
`Label("Settings", systemImage: "gearshape")` — an icon above a label.

Not a discovery; I wrote it that way to get the shell standing and did not want
it to pass unrecorded, because it looks deliberate on screen. The two glyphs
need drawing the way `PatientGlyphs.kt` draws `house.fill`, and one of them
already exists there.

---

### F-14 — Four screen-level ports are missing entirely, not merely unwired
**All phases · Important · found 2026-08-02 by a completeness sweep**

`PHeader` turned out to have never been ported — I only noticed because the
patient shell could not be built without it. That prompted a name-based sweep of
all 61 iOS view files against the Android tree, and it found more of the same
shape. These are **absent**, not built-and-unreachable, which is a different
problem from F-10:

| iOS file | Lines | Android | Note |
|---|---|---|---|
| `PHeader.swift` | 76 | **FIXED 2026-08-02** | now `ui/patient/PatientHeader.kt` |
| `PatientDetectorsView.swift` | 156 | **missing** | the Detectors tab's own list. `DetectorRow` and `DetectorDetailSheet` exist; nothing composes them |
| `EventReviewView.swift` | — | **missing** | review step 2. `EventsListViewModel.submitReview` is written and tested and has no caller |
| `SupportContactView.swift` | — | **missing** | `SupportUiState` exists, the screen does not |
| `PrivacyPolicyView.swift` | — | **missing** | no match anywhere |

**Why the phase reviews could not catch this.** Every review checked that what
was built matched the Swift. None checked the inverse — that everything in the
Swift got built — because each phase's scope was itself defined by a list I
wrote. A file I omitted from the list was invisible to every gate afterwards,
including the test suite, which can only test code that exists.

`SensorSetupWizard` and `TutorialOverlay` are also absent but are *known* absent
(Q5 and the plan's deferred list), which is the difference that matters.

**The remedy is not to port these four now.** It is that the parity gate has to
include a checklist derived from the iOS file list rather than from mine.
`PARITY.md` §29 lists behaviours; nothing lists *files*.

---

### F-15 — Adversarial review of the integration work: 3 Critical, 7 Important
**Integration · reviewed 2026-08-02 · Criticals FIXED same day**

A dedicated adversarial pass over `92753c1..cd5f89e`. It was asked to verify my
own claim that I had scanned for further instances of F-12; it re-derived the
scan independently and confirmed it, then found three Criticals I had not.

**C-1 — `AppEnvironment` was built per Activity while the ViewModels were
retained. FIXED.** Its own KDoc says "one instance, created at process start";
`MainActivity.onCreate` contradicted it. A `ViewModelStore` survives a
configuration change and `onCreate` does not, so changing the system font size,
display size, locale, or entering split-screen built a *second* environment —
new HTTP client, new Realtime socket, empty cache — handed it to the shells, and
left the retained ViewModels holding the first. Nothing cancelled the old scope,
so every recreation leaked another Realtime socket and another one-second camera
poll for the life of the process. The caregiver's symptom was being thrown out
of a patient back to the roster. Fixed by moving the environment into
`MemoriaApplication`.

**C-2 — nothing was wired to the app lifecycle. FIXED.** `NemaraCaregiverApp.swift`
drives `resync()`, `rebuildChannels()`, `startStatePolling()` and
`stopStatePolling()` off `scenePhase`; none had a counterpart, and `resync()` and
`rebuildChannels()` had **zero production callers**. The worst consequence: iOS
gets "stop polling" free because the OS suspends the process, and **Android does
not** — the poll was issuing one camera fetch per patient per second,
indefinitely, backgrounded, on cellular. Fixed with a `ProcessLifecycleOwner`
observer in `MemoriaApplication`.

**C-3 — switching patient A → B carried A's whole shell across. FIXED.**
`PatientShell` documents its teardown as firing when the caregiver leaves the
patient. The sub-tab half was correct; this half was not. `MainShell` rendered
`patientContent(openPatientId)` at a fixed call site, so A → B *recomposed* the
same shell instead of replacing it. `DisposableEffect(Unit)` never fired, so once
LiveKit is wired **patient A's room would stay connected inside patient B's
shell — one household's live video under another household's name.** Already
live: the events feed poll kept refreshing A's page and never B's. `DIFFERENCES.md`
D1 claims "observable behaviour: identical" for dropping iOS's two-tick
dismiss/present dance; it was not, because that dance was the only thing making
the shell tear down on a patient switch. Fixed with `key(openPatientId)`, and the
feed poll re-keyed on the ViewModel.

**Also fixed now:** I-1 (three documented cache side effects — `refineDetector`'s
non-preview swap, `uploadPatientPhoto`, `deletePatientPhoto` — had no owner at
all, exactly the risk `DIFFERENCES.md` D15 names), I-3 (the sign-out privacy
clear was elvis-chained behind an unguarded suspend call, so it ran only when
there was no Realtime store, i.e. never), I-4 (leaving a patient reset the main
tab bar to Home because `rememberSaveable` sat below the early return).

**Still open from this review:** I-2 (per-patient ViewModels are never cleared —
N stale copies each answer the next notification tap with their own authenticated
fetch), I-5 (`PatientShell` drops the notification-steers-to-Events behaviour and
the first-open data-collection consent sheet), I-6 (the Home button is an 18dp
touch target and is the only exit from the patient bundle), I-7 (the cache tests
aim away from the class of effect D15 says the split endangers), and M-1..M-7.

---

### F-16 — I committed a deliberate test mutation into main source
**Process · Critical (caught) · 2026-08-02**

Commit `58ba95a` used `git add -A` while an agent was mid mutation-test, and
captured `PatientDetectorsUiState.kt` with its M1 mutation applied:

```kotlin
val customs: List<Detector>
    get() = detectors.filter { it.source == Detector.Source.PRESET }   // was CUSTOM
```

Every detector would have been listed under **both** BUILT-IN and CUSTOM. It is
plainly visible on screen, so it would not have survived long — but it was in a
commit, it was in a green suite, and the only reason it did not stay there is
that the agent reported it in its own write-up. **I did not notice, and no gate
would have.** The agent's tests passed because it had already restored the file
in its working tree; `git add -A` picked up the wrong moment, not the wrong file.

**The rule this establishes:** never `git add -A` while agents are running.
Stage explicitly, or wait for every agent to report. Mutation testing means
main-source files are deliberately broken for seconds at a time, and a blanket
stage is a coin flip over that window.

Related: the same window produced repeated Gradle failures from several agents
running `testDebugUnitTest` against one shared build directory
(`~/.gradle-builds/memoria-caregiver-android`), which surface as
`EOFException` or `NoSuchFileException: in-progress-results-generic.bin` and
report a stale summary. Not a code defect, but it makes "the suite is green"
unreliable exactly when several agents are finishing at once.

---

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

### F-11 — The API client implements 2 endpoints out of ~26; F-10 understated the gap
**All phases · Critical · verified 2026-08-02**

`NemaraApiClient` has five methods against `/v1/me` and `/v1/support`. The iOS
`NemaraAPIClient.swift` is **1528 lines with ~60 public methods** across eight
resource families. Missing entirely:

| Family | iOS methods | Android |
|---|---|---|
| patients (incl. photo upload/URL/delete, test email) | 8 | 0 |
| cameras (pairing, discovery, provisioning, wandering doors) | 13 | 0 |
| detectors (infer, refine, library) | 9 | 0 |
| events (paging, clip URL, review) | 5 | 0 |
| LiveKit (tokens, talk start/end, clip record) | 6 | 0 |
| face embeddings | 4 | 0 |
| device tokens, feedback | 3 | 0 |
| lifecycle (bootstrap / refresh / teardown / resync) | 6 | 0 |

**This is why F-10 is not merely a wiring job.** A ViewModel cannot be written
against endpoints that do not exist, so the missing API layer sits underneath
every screen that is unreachable. `RealtimeStore`, `CameraStatePoller` and the
four channel handlers are all built and tested against `RealtimeCache` — an
interface `NemaraApiClient` is documented to implement and does not.

The contract is already written and does not need designing: `RealtimePorts.kt`
specifies the cache shape, the sort orders and the single-threaded confinement,
and `PARITY.md` §10 forbids splitting it into repository + store.

---

### F-12 — Concurrent detector loads dropped a patient's whole list
**Integration · Critical · FIXED 2026-08-02, found by a test**

`loadDetectors` was written as a single expression:

```kotlin
detectorsByPatient = detectorsByPatient + (patientId to detectorsApi.loadDetectors(patientId))
```

Kotlin evaluates the left operand first, so the map is **read before the fetch
suspends**. `bootstrap()` loads every patient concurrently, so with two patients
both coroutines read the same pre-fetch map and the second write erased the
first: **a caregiver with two patients saw one of them with no detectors at
all** — no error, no empty state, just a household with its monitoring silently
missing.

Single-threaded confinement does not prevent this, which is the part worth
remembering. The client's own comment says "single-threaded by contract", and
that is true and still insufficient: a suspension point between the read and the
write is an interleaving point even on one thread. `loadCameras` and
`loadEventsFirstPage` happened to be safe only because they hoist the fetch into
a local first.

Fixed by hoisting. I then scanned every cache assignment for the same shape —
`patients = patientsApi.loadPatients()` is a whole-value replacement with no read
on the right, so `loadDetectors` was the only instance.

**Found by the cache tests, not by review.** I wrote this code, read it back
while writing the commit message that described the single-threaded contract,
and did not see it. It is the first defect in this port caught by a test rather
than by a person.

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
carries the two role strings. Both lowercase calls name `Locale.ROOT`, pinned by
a test that runs under a Turkish default locale.

**Correction, later the same day.** I described the two copies' differing
lowercase spellings as a latent default-locale bug. That was wrong: Kotlin's
no-argument `lowercase()` already uses `Locale.ROOT` — it is what replaced
`toLowerCase()`, which did use the device locale. The spellings were always
equivalent, naming the locale is documentation rather than a fix, and the test
guards a stdlib guarantee rather than one this code adds. The duplication was
real; that one detail of the diagnosis was not.

The three duplicate tests in
`TalkModeTest` went away with the copy they asserted against.

---

### F-9 — Dead date formatters left behind by the codec collapse
**Phase 2 · FIXED 2026-08-02**

Deleting the forked `MiscInstantSerializer` left its five formatters
(`MICROSECOND_ISO8601`, `FRACTIONAL_ISO8601`, `PLAIN_ISO8601`, `DATE_ONLY`,
`OFFSET_PARSERS`) behind in `data/model/MiscModels.kt`, unreferenced. Private,
so they could not drift back into use, but stale code in the very commit whose
purpose was removing the fork. Deleted.
