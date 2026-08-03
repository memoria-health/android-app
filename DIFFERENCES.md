# DIFFERENCES.md

Every place the Android app deliberately diverges from iOS, with the reason.

The default is parity. An entry here is an exception that had to be justified.
Never delete an entry; if a divergence is later closed, mark it CLOSED with the
date.

Two legitimate reasons for a divergence:
- **Platform mechanism differs** and the observable behavior is preserved.
- **iOS carries a workaround for an iOS-only bug** that does not exist on Android,
  so porting it would import a defect.

An entry is NOT justified by "cleaner," "more idiomatic on Android," or "better
UX." Those are redesigns; the port does not redesign.

---

## D1 — Deep-link navigation does not do the two-tick dismiss/present dance
**Category:** iOS-only workaround
**iOS:** `DeepLinkRouter.route()` sets `openPatientId = nil`, then sets the new
patient id on the next runloop tick, when switching between two different
patients.
**Android:** navigates directly.
**Why:** the dance exists solely because SwiftUI's `fullScreenCover(item:)`
cannot hot-swap to a new id in a single tick. Navigation Compose can. The
durable-state reconciliation model around it **is** ported — only the two-tick
workaround is dropped.
**Observable behavior:** identical.

---

## D2 — Keyboard dismissal is not applied per-tab-content
**Category:** iOS-only workaround
**iOS:** `dismissKeyboardOnTap()` is attached to each tab's content and
explicitly NOT to the root, with a comment that attaching a tap gesture to a
SwiftUI `TabView` swallows tab-bar taps on iOS 17/18.
**Android:** standard focus-clearing behavior, applied once.
**Observable behavior:** identical (taps outside a field dismiss the keyboard;
bottom-bar taps work).

---

## D3 — Per-developer config is gitignored, not committed-then-skip-worktree'd
**Category:** deliberate improvement to a security practice, not a UX change
**iOS:** `LocalAppConfig.swift` is committed with nil defaults, and each
developer marks it `--skip-worktree` and edits locally. In practice the committed
file at the port pin contains a real LAN IP (`10.0.0.66`).
**Android:** the file is gitignored from creation; a `.template` is committed.
**Why:** a developer's home network address cannot reach a commit. The resolution
order (local override → env → debug fallback → release constant) is unchanged,
and release builds still never consult the override.
**Observable behavior:** identical at runtime.

---

## D4 — Development hosts are IP-based, not Bonjour hostnames
**Category:** platform mechanism differs
**iOS:** debug fallbacks are `http://sunnys-macbook-air.local:54341` /
`:8002`, resolved via Bonjour/mDNS, which iOS does automatically.
**Android:** IP-based, because Android does not resolve `.local` without
`NsdManager` and adding service discovery for a dev convenience is not worth the
surface area.
**Observable behavior:** identical for any developer who sets their override.
Affects debug builds only; release is unchanged.

---

## D5 — Cleartext HTTP permitted only in debug, via network security config
**Category:** platform mechanism differs
**iOS:** `NSAppTransportSecurity.NSAllowsLocalNetworking` permits plain HTTP to
non-routable addresses; routable destinations still require TLS.
**Android:** a debug-only `network_security_config.xml` permitting cleartext to
RFC1918 ranges. Release does not include it.
**Observable behavior:** identical.

---

---

## D6 — Sensor Wi-Fi onboarding uses a different platform API
**Category:** platform mechanism differs — **largest single divergence in the port**
**iOS:** `SensorSetupWizard` uses `NEHotspotConfiguration` +
`NEHotspotConfigurationManager.apply()` to join the sensor's `Memoria-Setup-<CODE>`
access point, requiring the `com.apple.developer.networking.HotspotConfiguration`
entitlement. On join, **iOS automatically presents its own captive portal** for
home-Wi-Fi entry, and the app deliberately does NOT open an in-app browser (doing
so stacked a duplicate pop-up).
**Android:** no equivalent. The options are `WifiNetworkSpecifier` (API 29+, a
peer-to-peer style connection scoped to the app) or `WifiNetworkSuggestion`.
Critically, **Android does not auto-present a captive portal the same way**, so
the "iOS shows its own page, we just wait and poll" assumption does not transfer.
**Impact:** the three-step wizard's step 2 needs a different interaction design on
Android, and it is the one screen where a 1:1 port is not achievable.
**Status:** OPEN — needs a design pass, tracked as `QUESTIONS.md` Q5.
**Unchanged and portable:** the 6-character code normalization, the predicted SSID
string, the 3s discovery poll against `GET /v1/cameras/discovered`, the 120s
budget, the single-result auto-advance vs multi-result picker, the "Go back"
reset, and the whole `AssignSensorForm` provisioning step.

---

## D7 — Blur uses downscale-blur, not RenderEffect
**Category:** platform mechanism differs
**Decision date:** 2026-08-01
**iOS:** Core Image `applyingGaussianBlur(sigma: 36)` composited inside person
boxes.
**Android:** downscale → blur → upscale, at **all** API levels. Not `RenderEffect`.
**Why:** `RenderEffect` is API 31+, which would force an API branch and, at
minSdk 31, drop roughly 15% of devices (API 34 reached only ~54.5% as of April
2026, so API 31 is around 85%). That device tail matters for the India pilot. For a
sigma-36 blur at video frame rates, downscale-blur-upscale is **both universally
supported and faster** than `RenderEffect`, so there is no branch to write and no
devices to drop. minSdk stays **26**.
**Observable behavior:** equivalent blur strength; must be tuned against the iOS
output during the Phase 4 screenshot pass.

---

## D8 — Avatar photos load through an injected renderer, not a built-in
**Category:** platform mechanism differs
**iOS:** `NemaraAvatar` uses `AsyncImage(url:)`, which SwiftUI provides for free.
**Android:** a `LocalAvatarPhoto` CompositionLocal the app root provides.
**Why:** Compose has no URL image loader. The alternatives were to add Coil to
the design-system layer — a dependency nothing else needs, which is the "for
later" pattern `HARNESS.md §3` rejects — or to inject the renderer. Injected.
**Status:** the seam has **no provider yet**, so photos do not render and every
avatar shows its serif initial. That is the `.empty`/`.failure` branch of the
Swift `AsyncImage`, so it degrades exactly as iOS does while loading — but it
must be wired before any screen shows a real patient. Tracked as REQ-22.

---

## D9 — Country picker chrome is rebuilt, not inherited
**Category:** platform mechanism differs
**iOS:** `.searchable()`, `.navigationTitle()`, `.listStyle(.plain)` — four
declarative lines, and UIKit supplies the search field, nav bar and separators.
**Android:** the search field, nav bar, row insets and separators are drawn
explicitly, introducing about fifteen constants with no counterpart in the Swift.
**Why:** Compose has no `.searchable`. The chrome has to come from somewhere;
material3's `SearchBar` brings its own shape, elevation and color roles, which is
the same reason `NemaraButton` avoids `Button`.
**Known consequence:** those fifteen constants are **judgement, not ported
values**, so this screen is more likely than any other to drift visually from
iOS. It needs the screenshot pass more than the rest.

---

## D10 — Country rows use Inter, where iOS falls back to the system font
**Category:** deliberate, and it goes against literal parity
**iOS:** `CountryPicker.swift:62` sets **no** font on the sheet rows, so they
render in SF Pro at List body size. The trigger row three lines earlier *does*
specify `NemaraFont.bodyLarge`, so the omission looks deliberate — on iOS the
system font in a system List is invisible-by-convention.
**Android:** rows use `NemaraType.bodyLarge` (Inter 16).
**Why:** the literal port would be Roboto, because that is Android's system font.
A Roboto list inside an Inter app does not read as "matching iOS", it reads as a
bug. The *intent* — "this list looks native" — is preserved by using the app's
own body font.
**This is the one place in the port where intent parity beat literal parity**,
and it is recorded here precisely because the rule says that normally is not
allowed. If the screenshot pass shows it reading wrong, revert to the literal.

---

## D11 — The brand mark uses a circular corner, not Apple's continuous curve
**Category:** platform mechanism differs
**iOS:** `RoundedRectangle(cornerRadius: size * 0.2237, style: .continuous)` —
the app-icon squircle.
**Android:** `RoundedCornerShape` with the identical 0.2237 radius, which is a
circular arc.
**Why:** Compose foundation has no continuous-curvature shape. A hand-rolled
partial squircle would be an approximation that is hard to review and easy to get
subtly wrong; an exact radius with the platform's curvature is the honest
trade.
**Where it shows:** negligible at the real call sites (20-56dp); most visible at
large sizes, which the app does not use.

---

## D12 — Auth errors are matched on a typed code, not on English text
**Category:** platform mechanism differs
**iOS:** `mapAuthError` asks whether the Supabase error description *contains*
`"invalid login"` or `"invalid credentials"` and returns `.unauthorized` if so.
**Android:** matches `AuthRestException.errorCode == AuthErrorCode.InvalidCredentials`.
**Why:** the Kotlin SDK exposes a typed error code that the Swift SDK does not.
The substring test works until Supabase rewords a message, localizes one, or the
two SDKs word it differently — at which point a wrong password quietly starts
showing the generic failure copy instead of a sign-in error. The observable
behavior is what iOS *intends*; it just cannot drift.
**Fallback preserved:** iOS's substring test still runs when no typed code is
available, so an older Supabase deployment degrades to iOS's behavior rather
than to nothing. Tested.
**Note for iOS:** the same fragility exists there and is logged as
`HARDENING.md` H-7.

---

## D13 — Camera and microphone need an explicit runtime permission flow
**Category:** platform mechanism differs
**iOS:** declares `NSCameraUsageDescription` and `NSMicrophoneUsageDescription`
in Info.plist, and the OS prompts the first time the app touches the hardware.
The app writes no code for this.
**Android:** needs three separate things — a `<uses-permission>` in the
manifest, an explicit runtime request, and a check before every use. Missing any
one makes the feature fail at the point of use.
**Why it matters here:** before this landed there were **no permissions declared
at all**, and `TalkController.startCall` went straight to
`publishMicrophone(true)` while enrollment went straight to `bindToLifecycle`.
Talk mode and face enrollment **could never have worked on a real device**, and
every unit test still passed. That is the shape of defect both the suite and the
emulator are blind to.
**Deliberate:** the gate does NOT prompt on composition. The system dialog
follows a deliberate action — tapping Talk, starting enrollment — because
Android stops showing the dialog after a second refusal, and an app that
ambushes someone on screen entry burns that budget for nothing.
`android.hardware.camera` is declared `required="false"` so the app stays
installable on a device without one; iOS does not gate installation either.

---

## D14 — The render watchdog's two inputs are re-derived, not ported
**Category:** platform mechanism differs
**Required by:** `PARITY.md` §12 and `QUESTIONS.md` Q3, both of which say the
Android mechanism must be recorded here because only the *intent* ports.

**The intent, which is identical:** a live tile that has gone black repairs
itself within ~2s, and a healthy tile is never disturbed.

**iOS:** reads `VideoView.isRendering`, polls it on a `Timer`, and re-attaches
with `view.track = nil; view.track = track`. Both inputs come free from the
platform: the property exists, and the timer simply stops firing while the app
is suspended.

**Android:** neither input exists, so each is rebuilt.

1. **"Is it rendering."** `SurfaceViewRenderer` exposes no such property. A
   second, counting `VideoSink` is attached to the same track, and "rendering"
   means the frame count advanced since the previous poll.
2. **The poll's lifecycle.** A coroutine keeps running while the app is
   backgrounded, where frames legitimately stop. An unscoped poll would read a
   backgrounded tile as stalled and "repair" it on a loop. The poll therefore
   runs only while the lifecycle is at least STARTED — which is the faithful
   port of what iOS's `Timer` does implicitly, not an Android addition.

The repair is the Android shape of the same two lines: detach both sinks,
release the renderer's EGL surface, re-initialise against the room's EGL
context, re-attach. It only ever runs against a tile that is already black, so
the one visible transition is black to video.

**Why the mechanism had to change at all:** iOS needs the watchdog because it
reclaims the Metal surface on background. Android's surface lifecycle is
different, so the *trigger* is not the same event — which is precisely why the
port is by intent. Whether the Android failure actually occurs, and how often,
is unproven without REQ-2 and a physical device.

---

## D15 — The API client's HTTP calls are grouped into six files; the cache is not split
**Category:** file organisation
**iOS:** `NemaraAPIClient.swift` is one 1528-line type holding the URLSession, the
observable cache, the Supabase channels and the poll together. Its own comment
says an earlier project split this into repository + store and ended up with two
sources of truth that drifted.

**Android:** the *cache* stays exactly as iOS has it — one `NemaraApiClient`
owning one set of `StateFlow`s and implementing `RealtimeCache`. What is split
out is the HTTP: six `internal class XxxApi(http: HttpTransport)` files
(patients, cameras, detectors, events, LiveKit, enrollment) holding **no state at
all**.

**Why this is not the split `PARITY.md` §10 forbids:** the scar §10 records is
two places that both *remember* things. A group with no fields cannot be a
second source of truth — it is a bundle of call sites. Every cached collection,
every sort order and every live-state side effect stays in the one client.

**What it bought:** six units could be built at once instead of one 1800-line
file being written serially, and each group's wire format is testable in
isolation against a mock engine.

**The risk it carries, stated plainly:** a group that returns a value and the
client that stores it are now two places, so a cache side effect can be dropped
in between. That is guarded by each group naming, in a comment, the exact side
effect its caller still owes — `live.markEverOnline` after `loadCameras` and
`live.beginReconnectWindow` after `updateCamera` are the two that matter.

---

_(Add entries as they arise. Cosmetic gaps found in the Phase 8 screenshot pass
go here too, each with a reason or a fix.)_
