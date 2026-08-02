# Port prompt — Memoria caregiver iOS → native Android

Hand this to the coding agent as its opening instruction. It assumes the agent
has read access to BOTH the iOS repo and the new Android repo.

---

## MISSION

Port the Memoria caregiver iOS app to a **native Android app** with 1:1 feature
parity, in a new repository `nemara-caregiver-app-android`. Native Kotlin +
Jetpack Compose. No cross-platform framework. No React Native, no Flutter, no KMP.

This is a **port, not a rewrite and not a redesign**. Every screen, every state,
every error path, every piece of copy should behave the way iOS behaves today.

---

## SOURCE OF TRUTH

- iOS repo: `nemara-caregiver-app`
- Branch: `feat/gateway-camera-ownership`
- **Pinned commit: `a6c27f64afd93fb2c44d17c6c7eccd02928cae7c`**

Read the source at that exact commit. Do **not** read `main` — it is 18 commits
stale and does not represent shipping behavior. If you find yourself reasoning
about a file that differs from the pin, stop and say so.

Scale you are porting: 103 Swift files, ~22,200 lines, SwiftUI, Swift 6 with
strict concurrency.

---

## THE FIVE RULES (non-negotiable)

1. **No extras.** Match iOS behavior exactly. Do not add features. Do not
   redesign screens. Do not "improve" the UX, the copy, the iconography, or the
   information architecture. If iOS shows a spinner in an ugly place, so does
   Android. Parity is the deliverable; taste is not.

2. **Never guess silently.** When iOS behavior is ambiguous, or an Android
   equivalent does not exist, or a backend contract is iOS-shaped, STOP. Append
   the question to `QUESTIONS.md` with the file and line you were reading, your
   best guess, and what you need to proceed. Then continue with other work.
   Inventing a plausible behavior is the single worst failure mode here.

3. **Behavior parity, not line parity.** Write idiomatic Kotlin and idiomatic
   Compose. Do not transliterate SwiftUI view-builder structure into Compose. Do
   not reproduce Swift naming conventions. The observable behavior must match;
   the code should read as if an Android engineer wrote it.

4. **Every phase compiles and is verified before the next begins.** `./gradlew
   assembleDebug` must pass. Do not stack unverified work.

5. **Carry the "why" comments forward.** The Swift source contains hard-won
   incident comments — the Realtime subscribe hang, the APNs debug/release
   entitlement split, the deep-link intent-vs-reality reconciliation model, the
   local-network ATS exemption. These encode outages that already happened.
   Port the reasoning into the Kotlin comments, adapted to the Android
   mechanism. Do not drop them as "iOS-specific noise."

---

## STACK — DECIDED, DO NOT DEVIATE

| iOS | Android |
|---|---|
| SwiftUI | Jetpack Compose (Material3 **as substrate only** — theme it to Nemara tokens, never ship default Material look) |
| `MainTabView` + `NavigationStack` | Navigation Compose + `NavigationBar` |
| `@Observable` / `ObservableObject` | `ViewModel` + `StateFlow` |
| `async/await`, `TaskGroup` | coroutines, `supervisorScope` / `async` |
| `URLSession` + `Codable` | Ktor client (OkHttp engine) + `kotlinx.serialization` |
| supabase-swift (Auth + Realtime) | supabase-kt (`auth-kt`, `realtime-kt`) |
| livekit client-sdk-swift 2.x | `io.livekit:livekit-android` 2.x |
| Vision face detection | ML Kit Face Detection |
| AVFoundation capture | CameraX |
| `UserDefaults` | DataStore Preferences |
| Keychain (session) | supabase-kt encrypted settings store |
| APNs | FCM (**see escalation list**) |
| `project.yml` / xcodegen | Gradle version catalog (`libs.versions.toml`) |
| `Info.plist` usage strings | `AndroidManifest.xml` + runtime permission requests |
| `.xcassets` color sets | Compose `ColorScheme` + a `NemaraColor` object |
| bundled `.ttf` | `res/font` + `FontFamily` |

- `minSdk = 26`, `targetSdk = 35`, Kotlin 2.x, JDK 17.
- **No Room.** iOS persists nothing relational; it uses `UserDefaults` only
  (tutorial seen-state, people-blur preference, wandering-door cache, analytics
  install id). DataStore covers all of it. If you find relational state I have
  missed, escalate rather than adding Room.

---

## PHASES

Each phase ends with a named deliverable and a verification step. Do not start
phase N+1 until phase N verifies.

### Phase 0 — Parity spec (no Kotlin yet)

Read the entire iOS source at the pinned commit and produce **`PARITY.md`**:

- Every screen, in navigation order, with its route, its entry points, and its
  exit points.
- For each screen: the states it can be in (loading / empty / error / populated /
  permission-denied), and what the user sees in each.
- Every network call: method, path, request shape, response shape, and what the
  UI does on each failure mode.
- Every Realtime subscription and what UI it drives.
- Every permission prompt, when it fires, and what happens on denial.
- Every piece of local persistence and its key.
- Every edge case the Swift comments call out explicitly.

This file is the contract. A human reviews **this**, not the Kotlin. Get it
right and the rest is mechanical. Also produce `QUESTIONS.md` (may be empty).

**Verify:** human sign-off on `PARITY.md` before phase 1.

### Phase 1 — Repo scaffold + design system

Gradle project, version catalog, module layout, CI-less local build. Then port
the design system **before any screen**: the ~22 color tokens, the three font
families (Fraunces, Inter, JetBrains Mono), spacing scale, radius scale, and the
shared components (`NemaraButton`, `NemaraCard`, `NemaraField`, `NemaraChip`,
`NemaraToggle`, `NemaraAvatar`, `NemaraPulse`, `NemaraWaveform`, `NemaraMark`,
`NemaraEyebrow`, `CountryPicker`).

Doing this first is what stops every later screen from being restyled ad hoc.

**Verify:** a component gallery screen renders every token and component.
Screenshot it next to the iOS equivalents.

### Phase 2 — Networking + auth (the walking skeleton)

`AppConfig` equivalent (with the per-developer local override pattern — see
`LocalAppConfig.swift`, and keep it out of version control the same way), API
error envelopes, JSON coders, the Ktor client, Supabase auth service. Then the
auth screens: Welcome, Login, SignUp, ForgotPassword, ResetPassword, and the
Terms consent gate with its scroll-to-accept PDF requirement.

Sign-in must **not** block on Realtime — read `NemaraAPIClient.bootstrap()` and
the comment above the detached channel task carefully. That is a production
incident encoded in the code.

**Verify:** sign in against a real backend, land on an empty roster.

### Phase 3 — Roster + events

Roster, patient row, empty-roster state, patient shell, events list, event card,
event detail, event review. Realtime channels for `patients`, `cameras`,
`detectors`, `events` plus the state-poll backstop.

**Verify:** events appear live; killing Realtime degrades to polling without
breaking the app.

### Phase 4 — Live video (highest risk — budget accordingly)

LiveKit room subscribe, video rendering, the people-blur overlay path
(`BlurredVideoView` + `LiveKitFrameGrabber` + `PeopleBlurStore`), the call/talk
button and mic publish, camera switching, the active-camera contract.

The blur path is frame-level processing and needs a custom Android `VideoSink`.
It has no mechanical Swift equivalent. Escalate before improvising.

**Verify:** live feed from a real camera; blur toggles correctly; talk mode
publishes audio and ends cleanly.

### Phase 5 — Cameras, detectors, patient settings

Camera list/detail, add-camera, sensor setup wizard, pairing code, wandering
door picker + cache, detector library, add detector, detector detail sheet,
detector rows, and the settings cards (people blur, audio capture, data
collection + consent sheet, email alerts).

**Verify:** create a custom detector end to end, including the compile/refine
round trip.

### Phase 6 — Onboarding + face enrollment (second highest risk)

New patient flow, region/country picker, photo upload, and the face enrollment
flow: front camera, rear camera, coverage dial, enrollment coach, and the
coverage model.

`FaceCoverageModel.swift` is bespoke geometry over Vision's landmark output.
ML Kit's face output differs in landmark set and coordinate conventions. Do not
assume the math transfers. Escalate with a written comparison before porting it.

**Verify:** enroll a real face; coverage percentages track the iOS app on the
same subject.

### Phase 7 — Push + deep links

FCM registration, device registration against the backend, notification
display, tap handling, and the deep-link router. Port the intent-vs-reality
reconciliation model from `DeepLinkRouter.swift` — a tap sets durable state and
each navigation layer reconciles toward it. Do not replace it with a one-shot
event bus; the comment explains exactly why that fails.

Also the `nemara://` scheme as an Android intent filter.

**Verify:** cold-start tap and warm tap both land on the right event.

### Phase 8 — Remaining surfaces + polish

App settings, edit profile, privacy policy, support contact, feedback, tutorial
overlay, analytics, accuracy caveat notice. Then a screenshot pass: every screen
side by side with iOS, differences listed in `DIFFERENCES.md` with a reason for
each.

---

## ESCALATE, DO NOT GUESS

These have no correct answer available in the iOS source.

**Blocking policy: stop and wait.** Write the question into `QUESTIONS.md`, stop
work on that specific item, and move to the next unblocked task. Do **not**
best-guess it, do **not** stub it with a plausible implementation, and do **not**
work around it. A wrong assumption that compiles and looks finished is more
expensive than an idle item, because it survives review. Surface the question
immediately when you hit it rather than batching questions to the end of a phase.

1. **FCM device registration.** The backend's `/v1/me/devices` and
   `/v1/me/devices/{token}` are APNs-shaped. There is no FCM path server-side
   today. This is a backend change, not an Android one.
   *Unblocked and in scope:* FCM token retrieval, notification channel setup,
   notification display, and tap handling — all of which are purely client-side.
   *Blocked:* the registration request shape against the backend. Build up to
   the call site, then stop and ask.
2. **The face coverage math** (see Phase 6).
3. **The blur frame pipeline** (see Phase 4).
4. **Legal documents.** The ToS and privacy PDFs currently shipping are
   placeholders with a version string of `2026-07-26-placeholder`. Use the same
   version string and the same scroll-to-accept gate; do not author replacement
   copy.
5. **Any backend endpoint that 4xx's differently than iOS expects.** Report it;
   do not work around it client-side.

---

## OUT OF SCOPE

- Any feature not present in the iOS app at the pinned commit.
- Tablet layouts (iOS is `TARGETED_DEVICE_FAMILY: 1`, phone only, portrait only).
- Wear OS, widgets, App Actions.
- Play Store listing assets and release signing.
- Test coverage beyond what iOS has (iOS has 8 model tests; match roughly, do
  not build a test pyramid the source app does not have).

---

## WORKING AGREEMENTS

- Commit per phase, minimum. Commit message states what was ported and what was
  verified, in that order.
- `QUESTIONS.md` and `DIFFERENCES.md` are living files. Never delete an entry;
  mark it resolved with the answer.
- If a phase runs long, deliver the phase partially and say exactly what is
  missing. Do not silently narrow scope.
- Do not modify the iOS repo. It is read-only source.
