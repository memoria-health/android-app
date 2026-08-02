# REQUIREMENTS.md — what each thing needs before it can be finished or trusted

Ledger of features, tests and functionality that carry a **requirement beyond
writing the code**: hardware, a credential, a backend contract, a test fixture,
a design decision, or real-world calibration.

Established 2026-08-01 at Aryaman's direction. Execution follows the phase plan
in docs/plans/2026-08-01-android-port.md; this file is what stops a requirement
deferred during a phase from being quietly forgotten.

**Rule:** anything parked here must also be visible where the work happens — a
`TODO(REQ-n)` at the call site, or a test annotated with the requirement id.
Never delete a row; mark it MET with the date.

Legend — **Blocks:** what cannot be completed. **Needs:** the requirement itself.

---

## Hardware required (cannot be satisfied on the emulator)

| id | Item | Needs | Blocks | Phase |
|---|---|---|---|---|
| REQ-1 | Blur pipeline performance | Physical device. Emulated GPU gives no usable frame-rate signal. | Tuning only — correctness is emulator-testable | 4 |
| REQ-2 | Live video against real cameras | A real Tapo camera + gateway on the LAN publishing `cam_*` to LiveKit | End-to-end video validation. Synthetic LiveKit publisher covers the client path. | 4 |
| REQ-3 | Sensor Wi-Fi AP join | Real Wi-Fi radio; emulator has no stack | All of `SensorSetupWizard` step 2 | 5 |
| REQ-4 | Enrollment quality calibration | Real faces, real rooms, varied light | Threshold tuning only — the model and flow are emulator-testable | 6 |
| REQ-5 | Talk-mode audio round trip | Two real endpoints; emulator mic is host passthrough | Echo, gain and routing judgement | 4 |
| REQ-6 | Battery and thermals | Physical device over a long session | Any claim about sustained live view | 4 |

## Credentials and accounts required

| id | Item | Needs | Blocks | Phase |
|---|---|---|---|---|
| REQ-7 | FCM push | Firebase project + `google-services.json` (gitignored) | Push receipt end to end | 7 |
| REQ-8 | Backend device registration | Backend decision on `/v1/me/devices` — `platform` discriminator vs sibling route (`QUESTIONS.md` Q1) | The registration call only; token retrieval and display are unblocked | 7 |
| REQ-9 | Play signing identity | Upload key + Play Console entry | Any release build. Debug is unaffected. | later |
| REQ-10 | Real ToS / Privacy PDFs | Veritas documents (`QUESTIONS.md` Q4) | Nothing — placeholders ship, swap on both platforms together | 8 |
| REQ-11 | Test caregiver account | A seeded account against a real backend | Auth E2E and every screen that needs a session | 2 |

## Test fixtures to author

| id | Item | Needs | Blocks | Phase |
|---|---|---|---|---|
| REQ-12 | Face yaw-sweep clip | A recorded video of a face turning through the yaw range, fed via `-camera-front videofile:` | Deterministic enrollment tests. **This is what makes REQ-4 tuning-only rather than blocking.** | 6 |
| REQ-13 | Synthetic LiveKit publisher | A test room publishing a known video pattern | Client-side video tests without REQ-2 | 4 |
| REQ-14 | iOS screenshot corpus | A **built, signed-in** iOS app at the port pin. Investigated 2026-08-01: `xcodegen` and iOS 17.2 simulators are available and the repo is clean on the pin branch, but the only prebuilt app is from May 22, months stale. Building is feasible; **populating the screens is not** — almost every screen needs a session and seeded data (REQ-11, REQ-16). SwiftUI `#Preview` blocks cannot be captured headlessly. | A true pixel-level parity gate | all |
| REQ-15 | Realtime payload corpus | Captured raw `postgres_changes` rows vs REST responses | The 10 lenient-decode rules (`PARITY.md` §7) — the dual-shape bug class | 3 |
| REQ-16 | Backend fixture / seeded state | A backend with patients, cameras, detectors, events | Any screen-level E2E | 2 |

## Design decisions still open

| id | Item | Needs | Blocks | Phase |
|---|---|---|---|---|
| REQ-17 | Sensor setup step 2 | An Android interaction design. iOS relies on the OS auto-presenting a captive portal; Android has no equivalent (`QUESTIONS.md` Q5, `DIFFERENCES.md` D6) | `SensorSetupWizard` step 2 | 5 |
| REQ-18 | Enrollment quality metric | A substitute for Vision `faceCaptureQuality`, which ML Kit has no equivalent for. Candidate: Laplacian variance + box area + luma, renormalized. **The 0.4/0.25 floors must be recalibrated against whatever is chosen** (`QUESTIONS.md` Q2) | Enrollment acceptance thresholds | 6 |

## Infrastructure not yet stood up

| id | Item | Needs | Blocks | Phase |
|---|---|---|---|---|
| REQ-19 | CI | A runner, plus prefetched Robolectric `android-all` jars — a cold runner otherwise fails in a way that looks like a real test failure (`DECISIONS.md` D-009) | Nothing today; every gate is local | later |
| REQ-20 | Off-machine backup | A remote. Currently commit-only, no push (`DECISIONS.md` D-007), in a cloud-synced tree already shown to corrupt builds (D-011) | Nothing, but the work exists in exactly one place | now-ish |
| REQ-21 | Instrumented test wiring | `androidTest` source set + runner + `connectedDebugAndroidTest` | Tier 3 of the ladder | 3 |

---

## What the parity gate actually is until REQ-14 is met

Stated plainly so nobody reads "screenshot pass" in the plan as something
stronger than it is. Three checks, none of which is a pixel diff:

1. **Token values, triple-confirmed.** The palette is now verified against three
   independent sources that agree: the iOS asset catalog, `nemara-web-app`'s
   `app/globals.css`, and the Kotlin. The web app declares the same token *names*
   with the same hexes — and carries the same deliberate aliasing
   (`--color-action: #1c1a14` = ink, `--color-danger: #a04331` = terra), which
   independently confirms that the value collisions in `PARITY.md §13` are
   intentional rather than transcription errors.
   Note `Success` and `Warning` exist on iOS but **not** in the web CSS, so those
   two are iOS-only and have only a two-source confirmation.
2. **Geometry, test-asserted against the Swift.** Every size, weight, padding,
   radius and tracking value is pinned by a unit test whose expected value was
   read from the Swift.
3. **Rendering, eyeballed on the emulator.** Catches what 1 and 2 cannot: font
   rendering, accumulated spacing, shape smoothing, baseline alignment. This is
   judgement, not measurement, and it is the weakest of the three.

The gap that remains is real: nothing here catches a component that has every
correct number and still looks wrong. That is what REQ-14 is for.

---

## Deliberately deferred, with the reason

- **Release minification.** Off. Turning it on needs kotlinx.serialization and
  LiveKit keep rules first, or R8 full mode strips serializers and it fails at
  runtime in release only. `app/proguard-rules.pro` records what is needed.
- **Dark mode.** Never. iOS has no dark variant of any token; there is no
  palette to port. Pinned by test.
- **Tablet and landscape.** iOS is phone-portrait only.
- **Localization.** iOS ships English only.
- **Module splitting.** Single `:app` until something forces otherwise.
