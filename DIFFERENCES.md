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

_(Add entries as they arise. Cosmetic gaps found in the Phase 8 screenshot pass
go here too, each with a reason or a fix.)_
