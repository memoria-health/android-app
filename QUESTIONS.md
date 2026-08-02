# QUESTIONS.md

Blocking questions raised during the port. **Policy: stop and wait.** When an
item here is unresolved, work on that item halts — no best-guess, no plausible
stub, no client-side workaround. Other phases continue.

Never delete an entry. Mark it resolved with the answer and the date.

---

## OPEN

### Q1 — FCM device registration contract
**Raised:** 2026-08-01, Phase 0
**Status: NOT A BLOCKER** (Aryaman 2026-08-01). FCM is assumed solvable and does
not have to mirror APNs. Scoped as separate backend work; the Android client
proceeds against the proposed contract below and adjusts if the backend lands
something different. Flagged here so the assumption is visible, not silent.
**Source:** `Models/DeviceRegistration.swift`, `NemaraCaregiverApp.swift:31-43`

`POST /v1/me/devices` currently takes:

```
{ "apns_token": "<hex>", "bundle_id": "<id>", "environment": "sandbox"|"production" }
```

and `DELETE /v1/me/devices/{apnsToken}`.

Three problems on Android:

1. `apns_token` is APNs-specific. FCM tokens are a different namespace and
   format, and the backend has no FCM dispatch path today.
2. `environment` is selected on iOS at **compile time** via `#if DEBUG`, because
   Apple's APNs entitlement is set at codesign time and the token registry must
   match or APNs returns `BadDeviceToken`. **FCM has no client-side equivalent** —
   one token, environment resolved server-side from the service account key. The
   field is meaningless on Android.
3. The backend has a **DB check constraint** accepting exactly `sandbox` |
   `production`, so we cannot simply send a third value like `fcm`.

**Decision needed (backend owner):** does `/v1/me/devices` grow a `platform`
discriminator with a nullable `environment`, or does Android get a sibling route?
Either is fine; the client code differs.

**Unblocked and proceeding meanwhile:** FCM token retrieval, notification channel
creation, notification display, tap payload parsing (`event_id` + `patient_id`
from the payload root), and the deep-link reconciliation. Only the registration
request is halted.

---

### Q2 — Face coverage: Vision → ML Kit
**Raised:** 2026-08-01, Phase 0
**Status: MOSTLY RESOLVED 2026-08-01** after reading the source. My original
framing was wrong, and the real gap is narrower and different.
**Source:** `Features/Onboarding/Enrollment/FaceCoverageModel.swift` (354 lines)

**I assumed this was landmark geometry. It is not.** The file's own header says it
is "Pure logic, no camera/UI/Vision dependency" — the camera feeds it
`ingest(yawDeg:pitchDeg:quality:framed:brightness:faceHeight:jpeg:)` and it returns
segments, a needle, and guidance. It never touches Vision types.

So **the entire model ports 1:1 to Kotlin.** All the tuning
(12 segments, frontal ≤10°, capture ≥22°, reach target 45°, 2 frames/segment,
2 frontal, 0.3s guidance dwell, 0.28 too-far height, 0.28 dark-luma) transfers
unchanged, as does the segment math, the most-extreme-frame retention, the needle
smoothing (α=0.4, render-only), and the debounced caption state machine.

Three of the four inputs map cleanly:
- `yawDeg` → ML Kit `headEulerAngleY`
- `pitchDeg` → ML Kit `headEulerAngleX`
- `faceHeight`, `brightness`, `framed` → computed from the bounding box and the
  frame, exactly as iOS does.

**The one real gap: `quality`.** iOS passes Vision's `faceCaptureQuality`, a
single 0-1 score. **ML Kit has no equivalent.** This matters because the model's
angle-aware quality ramp is calibrated to that scale: strict `0.4` frontal,
relaxing to `0.25` at full extreme, because capture quality intrinsically drops for
turned faces and a flat high bar would reject every profile even in perfect light.

**Decision needed:** what substitutes for `faceCaptureQuality` on Android.
Candidate: a composite of blur estimate (Laplacian variance on the face crop),
face-box area, and scene luma, normalized to 0-1. Whatever is chosen, **the 0.4 /
0.25 floors must be re-calibrated against it** — carrying the iOS numbers onto a
different metric would silently reject or accept the wrong frames.

Not blocking Phase 6 start: the model, the dials, and the capture loop can all be
built while this is settled, with the quality input stubbed behind one function.

---

### Q5 — Sensor Wi-Fi onboarding has no Android equivalent
**Raised:** 2026-08-01, Phase 0
**Blocks:** part of Phase 5 (`SensorSetupWizard` step 2 only)
**Source:** `Features/Patient/Cameras/SensorSetupWizard.swift` (673 lines)

iOS joins the sensor's `Memoria-Setup-<CODE>` AP via `NEHotspotConfiguration`, and
**iOS then presents its own captive portal** for home-Wi-Fi entry. The app
deliberately does not open an in-app browser, because that stacked a duplicate
pop-up. It just waits and polls discovery.

Android has `WifiNetworkSpecifier` (API 29+) and `WifiNetworkSuggestion`, but
**neither reproduces the automatic captive-portal presentation**, which is the
load-bearing part of the iOS flow. Step 2's interaction has to be designed, not
translated.

Everything else in the wizard ports unchanged: code normalization, predicted SSID,
the 3s discovery poll, the 120s budget, single-result auto-advance vs multi-result
picker, "Go back" reset, and the whole `AssignSensorForm` provisioning step.

**Decision needed:** the Android step-2 interaction. See `DIFFERENCES.md` D6.

---

### Q3 — Blur frame pipeline
**Raised:** 2026-08-01, Phase 0
**Status: NARROWED 2026-08-01** after reading the source. Most of this answered
itself; one real decision remains.
**Source:** `Features/Patient/Live/BlurredVideoView.swift`,
`Features/Patient/Live/LiveKitVideoView.swift`, `Services/LiveKitFrameGrabber.swift`,
`Services/PeopleBlurStore.swift`

**Answered.** The pipeline is person **bounding-box** detection plus a Gaussian
blur composited inside each box — not segmentation. iOS uses Apple Vision, but the
Swift comments state it mirrors the **web dashboard's COCO-SSD** approach, and the
tuning constants (pad 0.18, sigma 36, hold 1500ms, confidence 0.25, ~15/s
detection) are all annotated as web-mirrors. So Android should follow **web**, not
iOS: an SSD/MobileNet person detector via MediaPipe or TFLite, which yields the box
semantics those shared constants were tuned against.

**Still open — the blur primitive at `minSdk 26`.** RenderScript is deprecated and
`RenderEffect` is API 31+. Options:
1. `RenderEffect` on API 31+, with a GPU shader or downscale-upscale Bitmap blur
   fallback on 26-30.
2. A single GPU path (OpenGL/`GLSurfaceView` shader) for all API levels.
3. Raise `minSdk` to 31 and use `RenderEffect` only — **cheapest, but it drops
   Android 8-11 devices**, which matters for the India pilot device mix.

**Recommendation:** option 1. It keeps `minSdk 26` and confines the fallback to one
function. Needs a call before Phase 4 starts, and the device-mix question is
Aryaman's, not mine.

**Also flagged, not blocking:** the iOS render watchdog (poll `isRendering`, repair
a live-but-black tile after 2s, plus an unconditional re-attach on every
foreground) exists because iOS reclaims the Metal surface on background. Android's
`SurfaceViewRenderer` has a different surface lifecycle. The intent must be ported;
the mechanism must be re-derived and recorded in `DIFFERENCES.md`.

---

### Q4 — Legal PDFs
**Raised:** 2026-08-01, Phase 0
**Blocks:** nothing (documented, proceed)
**Source:** `Features/Legal/`

The shipping ToS and privacy PDFs are placeholders at version
`2026-07-26-placeholder`. Android uses the **same** documents and the **same**
version string, and reproduces the scroll-to-accept gate. Do not author
replacement copy. Swap when the Veritas documents land, on both platforms at once.

---

## RESOLVED

_(none yet)_
