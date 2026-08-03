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
**Integration · Minor · FIXED 2026-08-02**

Both bars now render icon + label, as `Label(title, systemImage:)` does. Six SF
Symbols drawn as vectors in `ui/nav/TabGlyphs.kt` — `house.fill`, `gearshape`,
`video.fill`, `waveform.path`, `camera.fill` and
`sensor.tag.radiowaves.forward.fill`. The last two are simplified to the part
that reads at 24dp; the full symbols close up to noise at tab-bar size, which is
noted at each one.

**Original entry, for the record:**

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
| `EventReviewView.swift` | — | **FIXED 2026-08-02** | `ui/events/EventReviewScreen.kt`; `submitReview` finally has a caller |
| `SupportContactView.swift` | — | **FIXED 2026-08-02** | `ui/support/SupportContactScreen.kt` + its ViewModel |
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

**Fixed 2026-08-02, after the Criticals:**

- **I-2** — per-patient ViewModels now live in a `ViewModelStore` scoped to the
  open patient (`ui/patient/PatientViewModelScope.kt`) and are cleared on leave.
  Previously the nearest owner was the Activity, so they accumulated for the
  session and **every surviving `EventsListViewModel` answered the next
  notification tap with its own authenticated `GET /v1/events/{id}`** for a
  patient the caregiver was not looking at.
- **I-5a** — a notification tap now steers the shell to Events. `PatientShell`
  takes a resolved `hasFocusedEvent` (guarded on the router pointing at *this*
  patient) and switches tab, without consuming the id — the events screen still
  owns clearing it.
- **I-6** — the Home button's target is 44dp with the glyph still 18dp, and the
  Feedback pill has a 44dp minimum height. It was an 18x18dp hit rect and is the
  **only** exit from the patient bundle, since the design has no back chevron.
  iOS's `Button { Image(...) }` gets UIKit's 44pt minimum for free.

- **I-5b** — the consent sheet is ported and wired. See F-17 for what porting it
  revealed about the consent design.
- **I-7** — 14 tests now cover every "caller still owes" cache side effect:
  both `loadCameras` refreshes (and that each still runs its `markEverOnline`
  pass), `unpairCamera`'s owner-scan reload including that a FAILED reload does
  not fail the unpair, the four row-swaps, and all three of the methods I-1
  found had no owner. 15 mutations run, 15 caught, no survivors and no
  collateral failures — so each assertion points at exactly what it claims. No
  further defects found.

**Still open:** M-1..M-7.

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

### F-17 — The data-collection consent record has no version, and six smaller gaps
**Product / backend · Important · raised 2026-08-02 while porting the sheet**

`DataCollectionConsentSheet` is now ported verbatim and wired. Porting it
surfaced seven things about the *consent design itself*. **None were changed** —
they are product and backend decisions, and a port is the wrong place to make
them. They are recorded because a port is a good place to notice them.

**1. No consent version is stored, and this is the one that matters.** The
decision is persisted as a boolean plus a `decided_at` timestamp. The ToS and
privacy gate stores a version string (`"2026-07-26-placeholder"`); this does
not. **If the wording ever changes, there is no record of which text any given
caregiver agreed to.** Under DPDP, consent is to a stated purpose — a consent
record that cannot reproduce the purpose it was given for is weak evidence at
best. Both platforms are affected equally; it is a schema question, not an
Android one.

The other six, in the sheet's own copy or behaviour:

2. No retention period, no purpose limit beyond "improve our understanding of
   elderly health and disease", and nothing about what happens to already-stored
   clips when the caregiver turns it off. "You can turn it off anytime" is
   forward-looking only.
3. Consent is captured from the **caregiver** alone. The person being recorded
   is never named as a consenting party.
4. "you can turn it off anytime in Settings" is shown to someone for whom
   nothing is on yet, so it reads as though collection has already begun.
5. The sheet promises "video and audio clips" unconditionally, but the audio
   capture card can sever audio at the source, so the audio half may be untrue
   for a given patient.
6. While a decision is in flight both buttons are inert but visually unchanged,
   and there is no spinner. Faithful to iOS — `NemaraButton` deliberately does
   not dim when disabled — but on a slow network a caregiver taps "Share
   recordings" and nothing appears to happen.
7. The error line renders `ApiError.errorDescription` verbatim, i.e.
   server-authored text inside a consent surface.

**What the port does get right, and deliberately:** a swipe-away sends nothing.
iOS presents the sheet without `interactiveDismissDisabled`, so dismissal is
possible and means the absence of an answer — `decided_at` stays null and the
server default stays off. The Android host passes
`onDismissRequest = dismissDataCollectionConsent` and **not** `onDecide(false)`,
because recording "no" for someone who only swiped is still recording a decision
they did not make.

---

### F-18 — Every toggle in the app was invisible to a screen reader
**Phase 1 · Important · FIXED 2026-08-02, found while testing the blur gate**

`NemaraToggle` used `Modifier.clickable(role = Role.Switch)`. That declares "this
is a switch" and publishes **no `ToggleableState`**, so TalkBack announced the
control as a switch and then could not say whether it was on or off. Every
toggle in the app was affected — people-blur, audio capture, every detector's
on/off, email alerts.

`Modifier.toggleable` gives the same tap and publishes the state. One-line fix,
45-ish call sites improved, no behaviour change for sighted users.

**Found by accident**, which is the part worth recording: writing the first
render test for `PeopleBlurCard` meant asserting `assertIsOn()` / `assertIsOff()`
on the switch, and those assertions cannot pass against a `clickable`. Nothing
else in 1751 tests had ever asked a toggle what state it was in.

Caregivers skew older and are a population disproportionately likely to be using
a screen reader, so this is not a theoretical accessibility box-tick.

---

### F-19 — The people-blur toggle no longer claims to be on
**Phase 4 · H-12 / REQ-23 · FIXED 2026-08-02**

The pipeline is complete and tested (box maths, padding, hold and decay,
downscale-blur composition, I420→ARGB, 59 tests) and `PersonDetector` has no
implementation, so the toggle blurred nothing while showing on.

`PeopleBlurAvailability.IS_AVAILABLE` is now the single build-time fact, read by
the card. While false the switch renders **off regardless of the stored
preference**, is non-interactive, writes nothing when tapped, and the copy says
"Not available in this version yet". The card stays visible rather than hidden,
so the control is discoverable — what must not happen is it reading as on.

A stored `true` cannot make the feature real, which is why the card derives
`effectivelyOn = enabled && available` rather than trusting the store.

When a detector is vendored, flipping that one constant re-enables it with no
other edit. H-12's underlying cause (REQ-23, no model) is unchanged and still
open; what is closed is the toggle lying about it.

**The card had no tests at all before this.** That is how a privacy control's
behaviour could have been changed, or the gate later removed, with a green suite
either way.

---

### F-20 — A duplicate status bar renders at the bottom of the support screen
**Integration · Minor · OPEN, cause not identified**

With `SupportContactScreen` on screen, a strip carrying the status-bar icons
(wifi, signal, battery and a clock glyph) is drawn near the BOTTOM of the
window, just above the navigation bar. Reproduced twice, two seconds apart. It
is gone the moment Cancel returns to Settings, and no other screen shows it, so
it tracks this screen rather than the device.

**Not diagnosed.** The plausible causes are an inset the screen consumes
differently from every other full-surface screen, or a Compose window-inset
interaction with the way it is presented over `MainShell` rather than as a
route. I stopped short of guessing further because the fix depends on which.

Cosmetic and non-blocking — the screen is usable, its fields and both actions
work, and Cancel exits correctly — but it is exactly the kind of thing a
screenshot parity pass exists to catch, and it should be understood rather than
nudged until it disappears.

---

### F-21 — Storage URLs cannot serve the emulator and a real device from one value
**Environment · Important · OPEN, not an app defect**

REQ-22 is wired: `LocalAvatarPhoto` now has a provider (Coil, declared at the app
layer so the design system still owns no image dependency, per D8). Verified
server-side end to end — a photo uploads, `photo_storage_key` is set, and
`GET /photo-url` returns a signed URL that fetches **200 from the host**.

It still does not render on the emulator, and the reason is the environment, not
the code:

- The app reaches the backend at **`10.0.2.2:8002`** — the emulator's alias for
  the host. That works.
- The backend builds signed storage URLs from `NEMARA_SUPABASE_URL`, which must
  be the host's **LAN IP** (`10.0.0.248`) so that a *real* device can fetch them.
  It is split-horizon: the container calls Supabase with it AND hands it to
  clients.
- `10.0.2.2` is meaningful only inside the emulator; the LAN IP is meaningful
  only outside it. **One value cannot satisfy both.**

The avatar degrades to its serif initial, which is the `.failure` branch of
iOS's `AsyncImage` — so the behaviour is right even though the image is absent.

**Not hacked around.** A debug-only rewrite of the storage host to `10.0.2.2`
would make the emulator work and is defensible, but it is a real design decision
about dev configuration, so it is Aryaman's call rather than mine. The
alternative is to verify photos on a physical device, alongside REQ-1/REQ-2
which need one anyway.

Until then: **no avatar photo can be confirmed rendering on the emulator**, and
REQ-22 should be read as "wired, server side proven, client side unproven".

---

### F-22 — Screenshot parity works, and its first run found two gaps
**Parity · 2026-08-02 · both FIXED same day**

The harness exists: `scratchpad/ios-parity/run.sh` captures all nine iOS screens
(welcome, login, roster, app settings, and all five patient tabs) in one
idempotent command. **It never touches `nemara-caregiver-app`** — XCUITest can
drive an installed app by bundle identifier without owning its source, which is
what unblocked this after weeks of assuming a test target had to be added to a
deployed repo.

The first side-by-side comparison, of the roster alone, found two things no unit
test could:

**1. The perpetual support button was missing entirely.** iOS applies
`.supportButtonOverlay()` on `RootView` **and again** on `PatientShell` — twice,
because the patient cover hides the presenter-level overlay. `SupportButton.kt`
existed on Android, built and tested, and **was applied nowhere**. The same
built-but-unwired shape as F-10, and it took a screenshot to see it.

**2. The selected tab was the wrong colour.** `NemaraCaregiverApp` applies
`.tint(NemaraColor.terra)`, which SwiftUI uses for the SELECTED tab item.
Android used Ink for both states, so the active tab was the same colour as the
inactive ones and only Material's pill distinguished it — and iOS draws no pill.
Now Terra with a transparent indicator, so the colour is the selection on both.

**Also confirmed by the comparison, and useful:** iOS renders Margaret's photo
while Android shows the serif initial. That is F-21 exactly — the iOS simulator
shares the host network so the LAN-IP storage URL resolves, and the Android
emulator cannot reach it. The comparison turned a suspicion into a demonstrated
difference.

**The harness's own finding, worth acting on:** the iOS app has **zero**
`accessibilityIdentifier`s — `grep` returns 0 hits across the app. Every
selector is bound to visible English copy, so any copy change or localisation
silently breaks the capture. Adding identifiers would make parity a durable gate
rather than a one-off, and would serve the Android port equally.

---

### F-23 — Every event detail after the first rendered the PREVIOUS event
**Integration · Critical · FIXED 2026-08-02, verified**

```kotlin
key = "eventDetail:${'$'}{event.id}",   // WRONG
```

`${'$'}` is Kotlin's escape for a literal dollar; `{event.id}` after it is plain
text. The key was the **constant string** `eventDetail:${event.id}` for every
event, so `viewModel(key = …)` returned the first instance ever created — built
capturing event A.

**What a caregiver saw:** open "Possible fall", back out, tap "Took meds" — the
detail still showed the fall's title, time, narrative and *the fall's clip*.
Then "Review this event" opened the review built from `openEvent`, which is the
**correct** event B. **They read one event and filed a review against another.**

Mine, and instructive: I wrote this file through a shell heredoc and escaped the
`$` to stop the shell interpolating it. The escape leaked into the Kotlin. The
three sibling keys three lines above interpolate correctly, which is what made
it invisible on a read. Fixed by concatenating rather than interpolating.

---

### F-24 — "Contact support" worked exactly once per app process
**Integration · Critical · FIXED 2026-08-02, verified on device**

`SupportContactViewModel` was obtained with an unkeyed `viewModel()` outside any
`NavHost`, so its owner was the Activity and it outlived the sheet. `cancel()`
sets `dismissed = true` and nothing ever set it back. The second tap re-entered
with `dismissed` already true, the `LaunchedEffect` fired on first composition,
and the sheet closed in the same frame.

**What a caregiver saw:** the Settings row does nothing, silently, on every tap
after the first, until the app is killed. It also kept the previous caregiver's
typed subject and message resident across sign-out.

Fixed by giving the sheet its own `ViewModelStore` (the helper built for the
patient bundle, now `ui/nav/ScopedViewModelStore.kt`), so closing clears it.
Verified by opening the sheet, backing out, and opening it again.

---

### F-25 — Nothing handled the system Back button in the signed-in app
**Integration · Critical · FIXED 2026-08-02, verified on device**

`grep -rn "BackHandler|onBackPressed" app/src/main` returned **zero hits**. The
signed-out flow was fine because Navigation Compose installs its own; everything
signed-in is a conditional branch rather than a route — the patient bundle,
event detail, event review, the support sheet. In all four, Back **and the
gesture-nav edge swipe, which is how most Android users close a screen** fell
through to the Activity and quit the app.

**What a caregiver saw:** mid-way through typing review notes on a fall, an
instinctive edge swipe closed the app and lost the notes.

iOS has no equivalent gesture, so nothing in the port suggested it was needed —
the same "behaviour iOS gets free from the platform" shape as every Critical in
the previous review, running the other way. Handlers added at all four levels.

---

### F-26 — Phase 7's push stack does not exist, and `nemara://` is dead
**Phases 7 and 2 · Critical · PUSH STILL OPEN · deep links FIXED 2026-08-03**

Two separate holes, both verified absent across the whole tree. **The deep-link
half is fixed and verified on the emulator; the push half is untouched and this
finding stays open for it.**

#### Deep links — fixed 2026-08-03

`nemara://reset-password?code=…` now resolves to `MainActivity`, and the code it
carries reaches the reset screen. Scheme and host are taken from
`AppConfig.supabasePasswordResetRedirect`, which is the address Supabase is
actually told to send, and they match the iOS `CFBundleURLSchemes` entry in
`project.yml`.

- **Manifest.** A second intent filter with `VIEW` + `DEFAULT` + `BROWSABLE` and
  `scheme="nemara" host="reset-password"`. `BROWSABLE` is what lets the link
  start us from Gmail rather than only from another app.
- **`launchMode="singleTop"`,** which is load-bearing rather than tidiness: under
  the default mode a link tapped while the app is running starts a *second*
  MainActivity, `onNewIntent` never fires, and the caregiver resets a password
  inside a duplicate copy of the app. Confirmed on the emulator — a warm start
  now reports *"intent has been delivered to currently running top-most
  instance"* and the task still holds one `ActivityRecord`.
- **Both entry paths.** Cold start reads `intent` in `onCreate`, guarded on
  `savedInstanceState == null` so a configuration change cannot re-spend a
  single-use code; warm start reads `onNewIntent` and calls `setIntent`.
- **The screen is a cover, not a route** (DECISIONS.md D-015): a route argument
  would put the recovery code in the back stack and therefore in the saved-state
  Bundle. It is held in `AuthViewModel.reset` instead, cleared on close and on
  success, and `ResetPasswordSession.toString()` is redacted so no log or crash
  report can print it. The code is never passed into the composition and never
  reaches an analytics label — the screen is reported as the constant
  `"reset_password"`, matching iOS's `.trackScreen`.
- **`BackHandler`,** because a cover is a conditional branch and nothing else
  handles Back (F-25). Verified on the emulator: Back closes the reset screen and
  lands on the welcome screen with the app still running.

42 tests across `RecoveryLinkTest`, `ResetPasswordSessionTest`,
`ResetPasswordViewModelTest` and `ResetPasswordDestinationTest`; suite 1843 →
1885. `RecoveryLinkTest` asserts against the *merged manifest* through
`PackageManager`, which is the only assertion that would have failed while this
was broken — every test of the screen itself passed throughout.

**One thing this exposed and did not fix:** when the link is opened on a device
that did not request it (or after app data is cleared), there is no stored PKCE
verifier and the screen shows the Supabase SDK's developer-facing sentence — *"No
code verifier stored. Make sure to use `getOAuthUrl` for the OAuth Url to prepare
the PKCE flow."* iOS has the same shape (`error.localizedDescription` for a
non-`APIError`), so this is a parity-preserving bug, not an Android one. Left
alone deliberately: the instruction is that error copy is the product's and is
rendered verbatim. It needs a product decision, not a silent reword.

#### Push — still open

The original entry stands unchanged for this half.

**Push.** No `firebase-messaging` or `google-services` dependency, no
`FirebaseMessagingService`, no `google-services.json`, no `POST_NOTIFICATIONS`,
no `<service>`, no notification channel. The *consumer* half is fully built and
green with **zero production callers**: `DeepLinkRouter.bufferEventTap` /
`drainPendingTap`, `registerDeviceToken` / `deleteDeviceToken`, and
`focusEventId` — which is read in three places and **written in none**. So the
tab steering, the focus consumption and the router's whole durable-target design
are unreachable. `MainShell` also never calls `drainPendingTap()` the way
`MainTabView.onAppear` does.

`QUESTIONS.md` Q1 blocks only the registration *call*, and `REQUIREMENTS.md`
REQ-8 says retrieval and display are unblocked — so per `HARNESS.md` §4c this is
missing, not deferred. **The fall alert never arrives; the app is a viewer you
must remember to open.**

**Deep links, as originally found (fixed above).** `MainActivity` had only a
MAIN/LAUNCHER intent filter — no `VIEW`/`BROWSABLE`/`scheme="nemara"`, no
`onNewIntent`, no `intent.data` read. `AuthNavHost` had no `RESET_PASSWORD`
destination and `AuthViewModel` had no reset methods, though
`exchangeRecoveryCode` and `updatePassword` both existed and were tested and
`ResetPasswordScreen` was 292 lines with no producer.
**Password recovery was impossible on Android**: the email sent, the link opened
nothing.

---

### F-27 — Account creation is a loop, so no Android account can be made
**Phase 2 · Critical · FIXED 2026-08-03**

All four pieces are wired. `SIGN_UP` now renders `SignUpDestination` (the real
`SignUpScreen`, with `TermsGateScreen` drawn over it when the terms row is
tapped, as iOS presents it as a sheet). `LegalConstants` has an Android home at
`com.memoria.caregiver.legal`, holding the version string the sign-up call sends
and the document list the gate renders — `TermsGateScreen`'s private copy of that
list is gone. `PdfPageLoader` is constructed by the host and passed as the gate's
`pageSource`, so the gate reads the bundled PDFs instead of its empty default.
`AuthNavHostTest` walks Welcome → Create an account → the form → the gate and
asserts the loader staged both real documents; `SignUpDestinationTest` covers the
three ways out of the gate, only one of which is consent.

**One thing this did not prove:** `PdfRenderer` does not work under Robolectric
(constructor dies on recent SDK levels, stubbed to zero pages on older ones), so
no unit test can watch a page rasterise. Everything up to the platform call is
tested; the rasterisation itself still needs a look on a device.

**Original entry, for the record:**

`AuthNavHost`'s `SIGN_UP` route renders `WelcomeScreen` again, whose `onSignUp`
pops back. Welcome → "Create an account" → an identical Welcome. The
`TODO(phase-2)` is now three phases stale.

Four things must land together and none is wired: `SignUpScreen` (586 lines) and
`TermsGateScreen` (430 lines) are built, tested and unreachable;
`LegalConstants.currentTosVersion` has no Android home at all; and
`PdfPageLoader` has **zero production callers**, so `TermsGateScreen`'s
`pageSource` default `{ emptyList() }` fires and the gate can never be satisfied
— even though H-9 records the PDFs as vendored and they are present in
`assets/legal/`. **H-9 must be reopened: the gate is bricked again, by a
different cause.**

Consequence beyond the loop: the ToS and privacy consent gate that ships
enforced on backend, web and iOS **has no Android enforcement path at all.**

---

### F-28 — Analytics is discarded, and the coverage gate has the hole it was built to close
**Phase 8 · Important · OPEN**

`MainActivity` provides `NoOpAnalytics`. There is no counterpart to
`Services/Analytics.swift` (295 lines: buffering, disk persistence, hourly
flush, batch insert, caregiver-id stamping, an enabled kill switch). **All 16
production call sites reach the bin**, and `logLifecycle` has zero callers where
iOS logs `app_launch` / `app_foreground` / `app_background`.

**Why the coverage gate missed it, which matters more than the finding:**
`HARNESS.md` §4c globs only `NemaraCaregiver/Features` and `NemaraCaregiver/App`.
`Services/`, `Models/`, `Networking/` and `DesignSystem/` are **never swept** —
exactly where `Analytics.swift`, `AnalyticsView.swift` and `WanderingDoorCache`
live. The gate written to catch missing files has the same blind spot that
produced them.

---

### F-29 — The events feed poll is not lifecycle-gated
**Phase 3 · Important · FIXED 2026-08-03**

Wrapped in `repeatOnLifecycle(STARTED)`, so the poll stops when the process
backgrounds and resumes when it returns — the same guarantee
`MemoriaApplication`'s observer gives the camera poll. A `LaunchedEffect` alone
does not survive-check anything: Android does not suspend the process the way
iOS does, which is the whole reason both polls needed explicit gating.

**Original entry, for the record:**

`PatientShell` launches `runFeedPoll()` from a `LaunchedEffect`; neither it nor
`viewModelScope` is cancelled when the process backgrounds. F-15 C-2 fixed
precisely this for the *camera* poll and nothing was done for this one. A
caregiver who leaves the app parked on Events keeps issuing
`GET /v1/patients/{id}/events` every 20 seconds, indefinitely, backgrounded, on
cellular. The fix has an obvious home in the `ProcessLifecycleOwner` observer
that already exists.

---

### F-30 — Three support and feedback entry points are built and unreachable
**Phase 8 · Important · PARTLY FIXED 2026-08-02**

`SupportButton` is now applied on both shells (F-22). Still unreachable:
`HelpChatSheet` (`HelpChatState` has no producer), `FeedbackScreen` (992 lines),
`rememberFeedbackScreenshotPicker`, `compressFeedbackJpeg` and
`EnrollmentApi.submitFeedback` — all zero callers. The patient header's Feedback
pill is wired to `onFeedback = {}`, so it renders on every patient tab and does
nothing. **Founders receive nothing from Android.**

---

### F-31 — Accessibility and touch targets, at scale
**All phases · Important · FIXED 2026-08-03 — and its touch-target half was WRONG**

**Fixed.** Every interactive element now declares a role: the eight duplicated
`plainClickable` helpers emit `Role.Button` internally, so all 46 of their call
sites get it and a new caller cannot forget. Controls that are not buttons got
the role that carries *state* — `Role.Tab` with selection for the segmented
pickers and filter chips, `Role.RadioButton` for single-choice cards,
`Role.Checkbox` for the multi-select ones. `contentDescription` went 18 → 30,
covering the drawn label-less controls (roster `+`, roster avatar, banner
dismiss, four back chevrons, two sheet closes). Autofill content types are wired,
so email, new-password and revealed-password fields now reach a password manager.
**Zero layout modifiers changed** — the whole main-source diff is semantics.

**The touch-target claim in this finding was wrong, and so were F-15 I-6 and
I-12, which rested on the same premise.** Measured rather than assumed: Compose
already inflates every clickable's hit rect to the platform 48dp minimum, and it
holds through `verticalScroll`, `Modifier.clip` and `PullToRefreshBox`. A 12dp,
a 20dp and a 44dp clickable all report a `touchBoundsInRoot` of exactly 48×48 —
so `assertTouchWidthIsEqualTo` cannot even tell them apart. A tap 10dp clear of
the 20dp roster `+` fires it; in the 12dp gap between that `+` and the avatar the
tap resolves to the nearest control, with no dead zone.

It fails in exactly one shape — a small clickable nested inside a larger
clickable, where the parent takes the strict hit and the child's expansion never
applies. All ten flagged sites were checked; none has a clickable ancestor. Both
measurements are pinned as behaviour tests so that if a future change introduces
a clickable ancestor, it fails loudly.

**Consequence for work already done:** the 44dp box I wrapped around
`PatientHeader`'s Home button (F-15 I-6) was unnecessary. It is harmless and
states the intent explicitly, so it stays — but the reasoning that produced it
was wrong, and "iOS gets 44pt free from UIKit, therefore Compose does not" was an
assumption I never measured.

---

### F-36 — Two host tests are flaky, independent of any recent change
**Integration · Minor · OPEN**

Full runs intermittently fail one of `NewPatientHostTest` / `AuthNavHostTest`;
each passes in isolation. Confirmed pre-existing: reverting the recent
onboarding/detector files and running `NewPatientHostTest` five times still
failed roughly one in five. Cause looks like mock-backend and real-file-I/O
races. Recorded rather than absorbed, because a suite that fails one run in five
teaches people to re-run instead of read.

---



80 `clickable` call sites in `ui/`; 15 declare a `contentDescription`; exactly
**one** declares a `role` — the `Role.Switch` from the F-18 fix. Every tappable
label iOS builds as a `Button` announces to TalkBack as unlabelled clickable
text with no button trait: settings rows, Enroll, Send test email, the roster
`+`, the events banner dismiss, both back chevrons. F-18 fixed toggle *state*;
this is the same shape one layer up, for control *identity*.

Ten further sub-44dp targets beyond F-15 I-6, including a **12dp** events-banner
dismiss and the 20dp roster `+` whose own comment says "the glyph IS the tap
target, as on iOS" — which is the reasoning F-15 I-6 already rejected.

Also: **no field in the app is offered to a password manager.** `NemaraField`
sets `KeyboardType.Password` and `semantics { password() }` but never
`ContentType.Password` / `ContentType.EmailAddress`, and Android autofill will
not offer a Compose `BasicTextField` without them. iOS gets it free from
`SecureField`. For a population that skews older and uses generated passwords,
that is a sign-in failure.

---

### F-32 — Twelve inert callbacks ship, and eleven of them look like broken buttons
**Integration · Important · FIXED and DEVICE-WALKED 2026-08-03**

Eleven callbacks now reach real screens. **Eight walked on the device**, tapping
by resolved `uiautomator` bounds rather than guessed coordinates — guessing is
what produced the retracted F-35, and one guess in this very walk hit the roster
avatar instead of the `+` and another launched Google Assistant:

| Destination | Result |
|---|---|
| roster row → patient shell | opens |
| Detectors → Add a custom detector | "New detector" |
| Detectors → Add from detection library | opens |
| patient header Feedback pill | "FROM THE FOUNDERS · We read every message." |
| patient Settings tab | "Margaret Doyle · Parent · Goes by Margaret · 78 yrs" |
| roster avatar → Edit profile | opens |
| Sensors → Add a sensor | Phone / Memoria™ Sensor tabs, room, icons, Generate code |
| Settings → Add another patient | "New patient · BASIC PROFILE" |

**Zero fatal exceptions across the entire walk.**

Not walked, because each needs something that does not exist yet: camera detail
(Margaret has no cameras), enrollment (needs the camera permission flow and a
camera), photo pick (needs the picker). `onPrivacy` remains an honest stub —
`PrivacyPolicyView` has no Android port (F-14).

**Original entry, for the record:**

`MainActivity` passes `{}` for twelve user-visible actions — add patient, edit
profile, privacy, patient feedback, camera detail, add sensor, detector
authoring, the library, detector edit, pick photo, enroll, patient feedback —
plus `onReplayTour` in `MainShell`. **Only the Live tab says on screen that it
is unbuilt.** The other eleven render as ordinary controls that do nothing.

---

### F-33 — The app crashed on EVERY sign-out
**Integration · Critical · FIXED 2026-08-03, reproduced and re-verified on device**

```
FATAL EXCEPTION: DefaultDispatcher-worker-1
java.lang.IllegalStateException: Websocket not yet initialized
    at io.github.jan.supabase.realtime.RealtimeImpl.getWebsocket(RealtimeImpl.kt:58)
    at io.github.jan.supabase.realtime.RealtimeChannelImpl.updateAuth(RealtimeChannelImpl.kt:191)
    at io.github.jan.supabase.realtime.RealtimeImpl$setAuth$3.invokeSuspend(RealtimeImpl.kt:251)
```

supabase-kt forwards every auth change to Realtime. Its `setAuth` iterates the
open channels calling `updateAuth`, which calls `getWebsocket()` — and by
sign-out time the socket is gone, so it throws **inside the SDK's own
`StandaloneCoroutine` on `Dispatchers.IO`**, where nothing of ours can catch it.
Process death.

`api.teardown()` ran in `onSignedOut`, i.e. **after** the Supabase sign-out that
triggers the event. Moving it to `onWillSignOut` closes the channels first, so
`setAuth` has nothing to iterate. The cache clear moving earlier is strictly
better anyway: it is a privacy boundary, and a care team sharing one device is
the normal case.

**Only a device could find this.** The throw is in a third-party coroutine, and
no unit test constructs a live Realtime client — 1843 tests were green with the
app crashing on every sign-out. It is also the exact signature I saw earlier in
a dirty logcat and declined to attribute without evidence; the evidence came
from walking the path deliberately.

**What a caregiver saw:** tap Sign out, the app disappears. On a shared device —
the normal case for a care team — that is the moment they hand the phone over.

---

### F-34 — Parity deltas from comparing captured screens
**Parity · Minor unless noted · OPEN**

Running log of differences found by putting the two captures side by side. The
harness (`scratchpad/ios-parity/run.sh`) has all nine iOS screens; Android
equivalents are in `scratchpad/shots/`.

**Roster — both fixed, see F-22:** the support button was missing entirely, and
the selected tab was Ink instead of Terra with a Material pill iOS does not draw.

**Detectors — content is byte-for-byte what iOS shows.** Same four detectors,
same descriptions, same `All rooms · Always` chips, same `4 ACTIVE` count, the
same wandering "Not active yet" notice and the same medication accuracy caveat.
Three deltas:

1. **The Detectors tab glyph is missing its emitter dot.** iOS's
   `sensor.tag.radiowaves.forward.fill` is a filled dot with arcs radiating from
   it; mine draws the arcs only, so it reads as a bracket rather than a sensor.
   My own simplification, noted in `TabGlyphs.kt` as "the readable core" — seeing
   it beside the original, the dot is the part that makes it legible.
2. **The Events tab glyph is a single zigzag where iOS draws a multi-peak
   waveform.** `waveform.path` on iOS has many short peaks; mine has one large
   one, so at a glance it reads as a chart rather than audio.
3. **The patient avatar looked letter-less on iOS — RESOLVED, not a difference.**
   `NemaraAvatar.swift` always draws `initialsBackground` (circle plus letter)
   and overlays the `AsyncImage`, which is `Color.clear` on both `.empty` and
   `.failure`. So the letter stays visible on iOS exactly as Android's doc
   claims. The iOS capture looked empty because iOS **loaded the photo** — and
   the photo I uploaded for testing was a downscaled screenshot of the mostly
   beige Welcome screen, so it rendered as a beige circle.

   This confirms F-21 a second way: the iOS simulator shares the host network and
   resolves the LAN-IP storage URL; the Android emulator cannot. **Nothing to
   fix in the avatar.** Recording it because "iOS shows no letter" was a
   plausible-looking parity bug that would have cost someone a day.

Six screens are captured and not yet compared: welcome, login, app settings,
patient live, patient events, patient sensors.

---

### F-35 — RETRACTED: "Add a sensor" works; I tested the wrong binary
**Integration · was Important · FALSE FINDING, retracted 2026-08-03**

I reported that `onAddSensor` was wired at every layer and did nothing on the
device, with uiautomator bounds, tap coordinates and a clean logcat as evidence.
**The evidence was real and the conclusion was wrong.**

A concurrent agent had installed its own APK over mine. It was building in an
isolated tree — `HEAD` plus only its own changes — precisely to avoid another
agent's broken compile, and said so in its report. That build did not contain the
F-32 wiring, so `onAddSensor` genuinely was `{}` **in the binary I tested**. The
control was clickable because the screen predates the wiring.

Rebuilt from the current tree and re-tested: the sheet opens with its Phone and
Memoria Sensor tabs, room field, icon picker and Generate code.

**The harness lesson, which is the reason to keep this entry rather than delete
it:** device verification is only valid against a binary you built *and* installed
with no agent running that might install its own. Everything else in this port
can tolerate concurrency; `adb install` cannot, because there is exactly one
device and the last writer wins silently. **Print the APK's build timestamp and
compare it to the commit under test before believing any device result** — I had
the timestamp in front of me (`02:48` vs a test at `02:47`) and did not check it.

What I got right: I recorded the finding with the raw evidence rather than a
conclusion, which is what made the retraction cheap and unambiguous.

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
**Phase 6 · Important · FIXED 2026-08-02**

`ui/enrollment/FaceCoverageModelSink.kt` joins the two halves. Two signature
differences, neither losing anything: the model takes a lazy `() -> ByteArray?`
so it can decline to pay for an encode, but `EnrollmentCamera` has already made
exactly that decision one step earlier, so wrapping the value re-satisfies the
shape without encoding twice; and the model's `ingest` return value is discarded
because the screen renders `model.state`, which already reflects the capture.

The model deliberately still does not declare the interface itself — it lives in
`data/enrollment/` and its header promises no camera, UI or ML Kit dependency,
which is what lets it be tested against recorded poses with no Android on the
path.

Five tests, four mutations, all four caught (see the harness note in
`HARNESS.md` §4d — the first run of those mutations produced four FALSE
"survived" results).

**Original entry, for the record:**

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
