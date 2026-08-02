# Android port — master worklist (2026-08-01)

> Canonical tracker for porting the Memoria caregiver iOS app to native Android.
> **Update THIS file as items land**; per-session trackers do not belong in the
> repo. Same convention as `nemara-backend/docs/plans/2026-07-28-pilot-master-worklist.md`.

## Ground truth (verified 2026-08-01, do not re-derive)

- **Port source pin:** `nemara-caregiver-app` @ `a6c27f64afd93fb2c44d17c6c7eccd02928cae7c`
  (branch `feat/gateway-camera-ownership`). `main` is 18 commits stale — never read it.
- Scale: 103 Swift files, 22,224 lines. SwiftUI, Swift 6 strict concurrency,
  iOS 17 target, **phone-only, portrait-only, light-mode-only**.
- iOS is a MOVING target: the pilot worklist still carries open iOS items
  (`suggested_name` on the create flow, rapid_frequency warning copy). Port
  against the pin; keep a delta list; replay at the end.
- Toolchain verified 2026-08-01 with a real Compose APK build (`BUILD SUCCESSFUL`,
  8.7MB debug APK): JDK 17.0.20 arm64, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21,
  Compose BOM 2024.10.01, compileSdk 35, minSdk 26, AVD `nemara_pixel` (API 35).
  JDK and SDK live outside Homebrew (the local brew is the Intel prefix on an
  arm64 Mac and would pull x86_64 builds).
- Scope decision (Aryaman 2026-08-01): **full parity**, all 8 phases, including
  LiveKit video, talk mode, and face enrollment.
- Escalation policy (Aryaman 2026-08-01): **stop and wait**. No best-guessing, no
  plausible stubs. See `QUESTIONS.md`.
- Device validation (Aryaman 2026-08-01): **deferred to day 9-10**. Accepted risk,
  recorded below.

## Phase 0 — parity spec (no Kotlin) — **COMPLETE 2026-08-01**

- [x] Read all 103 files at the pin and produce `PARITY.md`. **103/103.**
      29 sections covering app spine, networking, all 13 models, all 7 services,
      the full design system, and all 51 feature files.
- [x] `QUESTIONS.md` — 5 questions logged (Q1 FCM, Q2 enrollment quality metric,
      Q3 blur primitive, Q4 legal PDFs, Q5 sensor Wi-Fi). Q3 decided; Q1 downgraded
      to an assumption; Q2 and Q5 narrowed to single decisions.
- [x] `DIFFERENCES.md` — 7 deliberate divergences recorded with reasons.
- [x] minSdk + blur primitive decided: **minSdk 26, downscale-blur, no
      `RenderEffect`** (see `DIFFERENCES.md` D7).
- [ ] Human sign-off on `PARITY.md` before Phase 1. **This is the review gate that
      makes the rest reviewable** — at 22k lines the Kotlin cannot be reviewed directly.
      Aryaman directed (2026-08-01) not to block on go-aheads, so Phase 1 may start;
      read §29 "the ten things most likely to be ported wrong" first.

## Phase 1 — scaffold + design system

- [ ] Gradle project, version catalog, module layout.
- [ ] Design system BEFORE any screen: 22 color tokens, 3 font families
      (Fraunces, Inter, JetBrains Mono), spacing + radius scales.
- [ ] 11 shared components: NemaraButton, NemaraCard, NemaraField, NemaraChip,
      NemaraToggle, NemaraAvatar, NemaraPulse, NemaraWaveform, NemaraMark,
      NemaraEyebrow, CountryPicker (+ PatientAvatar, NemaraCamFeedPlaceholder).
- [ ] Verify: component gallery screen, screenshotted against iOS.

## Phase 2 — networking + auth

- [ ] AppConfig equivalent w/ 4-step resolution order; release never reads overrides.
- [ ] `network_security_config.xml` debug-only cleartext to RFC1918.
- [ ] Ktor client + kotlinx.serialization; the 4-pattern date fallback chain;
      `date_of_birth` as `YYYY-MM-DD`.
- [ ] APIError mapping w/ verbatim user-facing copy (9 cases).
- [ ] supabase-kt auth + **the authStateChanges → realtime setAuth pump**
      (silent failure if missed: RLS drops every gated row).
- [ ] Screens: Welcome, Login, SignUp, ForgotPassword, ResetPassword, Terms
      consent gate (scroll-to-accept).
- [ ] Sign-in must NOT block on Realtime (port the incident behavior + timeout).
- [ ] Verify: sign in against a real backend, land on empty roster.

## Phase 3 — roster + events

- [ ] Roster, PatientRow, EmptyRoster, PatientShell, events list/card/detail/review.
- [ ] 4 Realtime channels (patients, cameras, detectors, events) + 1s poll backstop.
- [ ] The 10 lenient-decode rules, each with a test (Realtime raw-row vs REST shape).
- [ ] Verify: events appear live; killing Realtime degrades to polling.

## Phase 4 — live video (highest risk)

- [ ] LiveKit room subscribe, video render, camera carousel, active-camera default.
- [ ] Blur overlay pipeline (custom VideoSink) — **Q3 open**.
- [ ] Talk mode: mic publish, talk/start + talk/end, role=broadcaster token.
- [ ] Private-room placard: privacyEnabled ⇒ no video/audio, talk disabled.
- [ ] Verify: **device-only, deferred to day 9-10.**

## Phase 5 — cameras, detectors, settings

- [ ] Camera list/detail, add-camera (phone 6-digit code vs sensor discovery),
      sensor setup wizard, pairing code sheet.
- [ ] Wandering door picker + cache; detect-doors VLM round trip.
- [ ] Detector library, add detector, detail sheet, rows, accuracy caveat notice.
- [ ] Settings cards: PeopleBlur, AudioCapture, DataCollection + consent sheet,
      EmailAlerts.
- [ ] Verify: create a custom detector end to end incl. compile/refine.

## Phase 6 — onboarding + enrollment (second highest risk)

- [ ] NewPatient flow, country picker, photo upload.
- [ ] Face enrollment: front + rear, coverage dial, coach — **Q2 open**.
- [ ] Verify: **device-only, deferred to day 9-10.**

## Phase 7 — push + deep links

- [ ] FCM token retrieval, channels, display, tap payload parse. **Unblocked.**
- [ ] Registration call site — **Q1 open, HALTED.**
- [ ] Deep-link router: durable state + reconcile-toward model (port), without the
      two-tick SwiftUI workaround (see `DIFFERENCES.md` D1).
- [ ] `nemara://` intent filter, incl. `reset-password?code=`.
- [ ] Verify: cold-start tap and warm tap both land on the right event.

## Phase 8 — remaining surfaces + polish

- [ ] AppSettings, EditProfile, PrivacyPolicy, SupportContact, Feedback,
      TutorialOverlay (1,026 lines — the second-largest feature file), Analytics.
- [ ] Screenshot pass: every screen beside iOS; gaps into `DIFFERENCES.md`.

## Review gates (Aryaman-mandated)

- [ ] Adjudicated multi-agent review at **every** phase end: correctness,
      efficiency, memory, security, goal-adherence.
- [ ] Full-diff review before each PR. Branch per phase; never straight to main.

## Accepted risks

- ~~**Device validation deferred to day 9-10**~~ — **LARGELY RETIRED 2026-08-01.**
  The original framing was that phases 4 and 6 "cannot be verified without a real
  device, real camera, and a real face", so everything would be compile-verified
  only until day 9, with no runway to redesign if it failed late.

  That was wrong about the Android Emulator, which unlike the iOS Simulator is a
  full VM running the real OS. Verified on `nemara_pixel`: the app installs and
  runs, the host backend is reachable at `10.0.2.2`, and both cameras are now
  present after fixing the AVD (it had shipped with `hw.camera.front = none`,
  which would have made enrollment untestable). `-camera-front videofile:` feeds
  a recorded clip as the camera, so the enrollment coverage model is testable
  deterministically and repeatably.

  **What genuinely still needs hardware — the whole list:**
  1. Blur performance at video frame rates (emulated GPU gives no usable signal)
  2. A real Tapo camera and gateway on the LAN
  3. Sensor Wi-Fi AP join (Q5) — no real Wi-Fi stack in the emulator
  4. Enrollment quality-metric calibration (Q2) — real faces, real light

  Items 1 and 4 are tuning, not correctness, so they fail *gradually* rather than
  catastrophically. That is the actual risk reduction: late failure now means
  "the blur is too slow" or "the threshold is off", not "the architecture is
  wrong". See `HARNESS.md §4b`.

- **Emulator perf is not device perf.** Nothing measured on the emulator counts
  as a performance result. Frame rates, blur cost and battery are day 9-10.
- **Repo lives under the cloud-synced tree** (Aryaman 2026-08-01). Known to abort
  merge/checkout/rebase via OneDrive/iCloud stat races; workaround is `update-ref`
  plumbing.

## Blocking dependencies owned OUTSIDE this repo

- [ ] **FCM backend contract** (`Q1`). Backend change, needs an owner this week or
      Phase 7 lands half-built.
- [ ] Real ToS/Privacy PDFs from Veritas (`Q4`) — swap on both platforms at once.
