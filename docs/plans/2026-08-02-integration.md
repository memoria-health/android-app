# Integration Plan — making the app a product instead of a parts bin

> **For Claude:** this plan closes `FINDINGS.md` F-10 and F-11. Read both first.

**Goal:** a caregiver can sign in, see their roster, open a patient, and move
through all five patient tabs against the real backend — with every one of the
24 built screens reachable.

**Why this exists.** Eight phases produced 1466 passing tests and an app whose
every feature screen is dead code. The units are real; the seams between them
were never built. Nothing in the test suite can tell those two states apart,
which is exactly why this is written down as a plan rather than treated as
cleanup.

**Architecture:** unchanged from `PARITY.md` §10 — one `NemaraApiClient` owning
one observable cache. No repository layer, no second source of truth.

---

## The order, and why it is this order

Bottom-up, because each layer is unusable without the one below it:

```
  1. API groups        ← pure HTTP, no state          (6 units, parallel)
  2. Client + cache    ← the StateFlows, RealtimeCache (1 unit, mine)
  3. ViewModels        ← state producers per screen    (parallel, after 2)
  4. Nav graph         ← the shells and routes         (1 unit, after 3)
  5. Wiring            ← bootstrap/teardown/deep links (1 unit, after 4)
```

Steps 3 and 4 are the ones that will surface parity defects, because they are
where a screen first meets real data. Budget review time there, not in step 1.

---

## Step 1 — API groups (pure HTTP)

Six `internal class XxxApi(private val http: HttpTransport)` files under
`data/remote/`. **They hold no state and touch no cache.** Where the Swift does
`self.patients = arr.sorted { … }`, the group returns the sorted list and the
caller assigns it.

| File | Swift functions |
|---|---|
| `PatientsApi.kt` | load/create/update/delete, photo upload/URL/delete, test email |
| `CamerasApi.kt` | load, pairing codes, discovery, provisioning, unpair, detectors, wandering doors |
| `DetectorsApi.kt` | load, create, infer, refine, delete, library |
| `EventsApi.kt` | first page, next page, fetch one, clip URL, review |
| `LiveKitApi.kt` | viewer/broadcaster token, active camera, talk start/end, record clip |
| `EnrollmentApi.kt` | face embeddings, device tokens, feedback |

**Why split at all**, when iOS is one 1528-line type: these are call bundles with
no state, so they cannot become a second source of truth — the thing `PARITY.md`
§10 actually forbids. The split is what let six units be built at once. Recorded
as `DIFFERENCES.md` D15.

**The one rule that matters most:** `LiveKitApi` must build no identity or room
string itself. `data/livekit/LiveKitIdentity.kt` is the single definition. A
duplicate of that exact string was found and deleted on 2026-08-02 (F-2), and a
wrong suffix silently evicts the caregiver's own iPhone from the live stream.

## Step 2 — `NemaraApiClient` grows the cache

Mine, deliberately, because this is where the parity risk concentrates:

- Implements `RealtimeCache` over `MutableStateFlow`s, exactly as
  `RealtimePorts.kt` already documents.
- Implements `CameraFetcher` (`fetchCameras` must NOT touch the cache — the poll
  compares fresh against cached).
- Owns the three cache-adjacent side effects the API groups deliberately skip:
  `live.markEverOnline` from `loadCameras`, `live.beginReconnectWindow` from
  `updateCamera`, and the event-page merge.
- `bootstrap()` / `refresh()` / `teardown()` / `resync()`.
- **Single-threaded by contract.** `RealtimeStore` confines everything to one
  dispatcher; the client must honour the same confinement or the four channel
  handlers and the poll race it.

## Step 3 — ViewModels

One per screen family, each a `ViewModel` exposing a `StateFlow` of the state
type the screen already takes, plus the callbacks it already declares. The
screens do not change: they are pure `(state, callbacks) -> Unit` composables
and that is why this step is additive rather than a rewrite.

## Step 4 — Navigation

Ported from `App/RootView.swift`, `App/MainTabView.swift` and
`Features/Patient/PatientShell.swift`:

- **Root:** signed-out → Welcome/Login/SignUp; signed-in → main shell.
- **Main shell:** two tabs, Home and Settings.
- **Patient shell:** five tabs — Live, Events, Sensors, Detectors, Settings —
  under a pinned `PHeader`, entered from a roster row, a settings row, or a
  notification tap, all through `DeepLinkRouter` and nothing else.
- **The single LiveKit teardown point** is leaving the patient shell, never a
  sub-tab switch. iOS gets this from `.onDisappear` on the outer `TabView`;
  Compose needs it stated explicitly or the room drops on every tab tap.

## Step 5 — Wiring

`api.bootstrap()` in `onAuthenticated`, `api.teardown()` in `onSignedOut`,
`DeepLinkRouter` into the nav host, and the two `TODO(phase-3)` markers in
`AppEnvironment` deleted.

---

## Gates

Per unit: compiles, its own tests pass, `./gradlew testDebugUnitTest` stays green.
Per step: a review pass against `HARNESS.md` §3 before moving down the list.
At the end: the emulator E2E below, then screenshot parity.

**E2E is the gate that matters here**, because it is the only one that can fail
in the way this whole plan exists to fix. Sign in against the local stack, land
on a roster with Margaret Doyle, open the patient, visit all five tabs, sign out,
confirm the cache is empty.

---

## Deferred inside this plan, on purpose

- **Live video** needs a real camera (REQ-2). The tab is reachable and shows its
  empty state; frames are not part of this plan's gate.
- **Enrollment** needs `EnrollmentCoverageSink` implemented (F-5) — small, but a
  separate change.
- **Push** stays blocked on REQ-8 / Q1.
- **The welcome tour** is last, as agreed.
