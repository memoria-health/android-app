# PARITY.md — iOS → Android port scope

> **Status: COMPLETE (2026-08-01).** All 103 files read at the pin. Every section
> is analyzed from source. This is the contract for phases 1-8.
>
> **Source pin:** `nemara-caregiver-app` @ `a6c27f64afd93fb2c44d17c6c7eccd02928cae7c`
> (branch `feat/gateway-camera-ownership`). Never read `main` — 18 commits stale.
>
> Scale: 103 Swift files, 22,224 lines, SwiftUI, Swift 6 strict concurrency,
> iOS 17 deployment target, phone-only, portrait-only, light-mode-only.

## Analysis progress

| Layer | Files | Status |
|---|---|---|
| App spine (`App/`) | 6 | ✅ |
| Networking (`Networking/`) | 4 | ✅ |
| Models (`Models/`) | 13 | ✅ |
| Services (`Services/`) | 7 | ✅ |
| DesignSystem (`DesignSystem/`) | 22 | ✅ |
| Features (`Features/`) | 51 | ✅ |
| **Total** | **103** | **✅** |

---

## 1. Architecture map ✅

Single dependency container (`AppEnvironment`) built once at launch and injected
through the view tree. Four members:

| Member | Responsibility |
|---|---|
| `auth: SupabaseAuthService` | Supabase Auth only. Sessions, sign-in/up/out, password reset, email change, account delete. |
| `api: NemaraAPIClient` | Backend HTTP + observable cache + Supabase Realtime subscriptions. |
| `liveKit: LiveKitClient` | One reusable LiveKit Room across patients. |
| `deepLink: DeepLinkRouter` | Which patient shell is open + which event to focus. |

**Composition is deliberately one-directional.** `auth` does not know about `api`.
`api` does not know about `liveKit`. `AppEnvironment` wires them with closures
(`onAuthenticated`, `onWillSignOut`, `onSignedOut`, `onDeleteAccount`,
`onCameraOnline`). **Preserve this.** Android equivalent: a DI graph where the
wiring lives in one module, not cross-injected ViewModels.

### Android mapping

| iOS | Android |
|---|---|
| `AppEnvironment` + `.environment()` | Hilt (or manual) singleton graph |
| `@Observable` classes | `ViewModel` + `StateFlow` |
| `RootView` auth gate | Top-level `NavHost` with auth/main graphs |
| `MainTabView` (2 tabs) | `NavigationBar` + nested nav graphs |
| `fullScreenCover(item:)` for PatientShell | full-screen destination in nav graph |

---

## 2. Navigation contract ✅

```
RootView (auth gate on auth.currentCaregiver != nil)
├─ signed out → NavigationStack { WelcomeView } → Login / SignUp / ForgotPassword
└─ signed in  → MainTabView
                ├─ tab: Home     → RosterView
                ├─ tab: Settings → AppSettingsView
                └─ fullScreenCover: PatientShell(patientId)   ← SINGLE presenter
```

**Hard rule from the source:** `PatientShell` is presented from exactly ONE place
(`MainTabView`), driven by `deepLink.openPatientId`. Roster taps, settings-row
taps, and push taps all funnel through that one field. Two presenters caused
cover fights; do not reintroduce per-screen navigation state.

`ResetPasswordView` is a separate full-screen cover at the **app** level (above
the auth gate), because a recovery link can arrive signed-in or signed-out.

### Deep-link model — port the MODEL, not the MECHANISM

`DeepLinkRouter` holds durable state (`openPatientId`, `focusEventId`) and each
navigation layer *reconciles toward it* when ready. This is because a push tap is
an event but navigation must survive async windows (auth gate → bootstrap → cover
presentation). **Keep this design** — it is the reason cold-launch taps work.

`route()` contains a dismiss-then-present-next-runloop dance purely because
SwiftUI's `fullScreenCover(item:)` cannot hot-swap to a new id in one tick.
**Navigation Compose can.** Do not port the two-tick workaround; a direct
navigate is correct on Android and produces the same observable behavior.

Same category: `dismissKeyboardOnTap` is applied per-tab-content rather than at
the root ONLY because a tap gesture on a SwiftUI `TabView` swallows tab-bar taps
on iOS 17/18. Android has no such bug.

---

## 3. App lifecycle contract ✅

| iOS trigger | Action | Android equivalent |
|---|---|---|
| `scenePhase == .active` | `api.resync()` → `api.rebuildRealtimeChannels()` → `api.startStatePolling()` | `Lifecycle.Event.ON_START` |
| `scenePhase == .background` | `Analytics.persistAndFlush()` → `api.stopStatePolling()` | `ON_STOP` |
| launch | request push auth; warm blur model | `Application.onCreate` / first composition |
| `auth.currentCaregiver?.id` changes nil→id | re-register push token to the new caregiver | observe auth state |

**Why the foreground resync exists:** the Supabase socket is severed while
suspended and Realtime has **no replay**. Three steps, in order: refetch what was
missed, rebuild channels with the current token, restart the 1s poll. All three
no-op before first bootstrap and when signed out.

**Push token re-binding:** the device token is per-device and the backend
reassigns it to the current caller. iOS only receives the token at launch, so
without re-registering on sign-in, switching accounts on one device leaves the
token bound to the previous account and the new one gets no pushes. Android has
the same hazard with FCM.

---

## 4. Configuration contract ✅

`AppConfig` resolves each field in strict order:
1. `LocalAppConfig.<field>` — per-developer override
2. `ProcessInfo.environment[KEY]`
3. `#if DEBUG` fallback
4. Release constant

**Release builds NEVER consult 1 or 2.** This is deliberate: `LocalAppConfig` is
skip-worktree'd, so a developer's LAN IP persists on the machine that cuts the
build. A distribution build that honored it would ship pointing at someone's Mac.

| Field | Debug fallback | Release |
|---|---|---|
| `supabaseURL` | `http://sunnys-macbook-air.local:54341` | prod Supabase project URL |
| `supabaseAnonKey` | local publishable key | prod publishable key |
| `backendURL` | `http://sunnys-macbook-air.local:8002` | Railway prod |
| `enableClipCapture` | override-able, default false | **hard-compiled false** |

### Android notes

- **Bonjour will not work.** The `.local` mDNS hostnames resolve on iOS
  automatically; Android needs `NsdManager`. The Android local-config equivalent
  must be **IP-based**.
- The Android `LocalAppConfig` equivalent must be **gitignored from creation**,
  not committed-then-skip-worktree'd. Do not put a real LAN IP in the repo.
- `enableClipCapture` must be hard-`false` in release the same way — it is the
  single choke point for the dev-only clip-capture chain (button → backend →
  camera record trigger). It must never ship enabled.
- iOS allows cleartext HTTP to private IPs via ATS `NSAllowsLocalNetworking`.
  Android needs a debug-only `network_security_config.xml` permitting cleartext
  to RFC1918, and release must NOT include it.

---

## 5. Error contract ✅

`APIError` maps the backend's FastAPI `{"detail": ...}` envelope.

| Case | HTTP | User-facing copy (port verbatim) |
|---|---|---|
| `badRequest(message)` | 400 | `Bad request: {m}` |
| `unauthorized` | 401 | `Couldn't verify your session with the server. Check your connection, or sign in again.` |
| `forbidden` | 403 | `You don't have access to this.` |
| `notFound` | 404 | `That isn't here anymore.` |
| `conflict(reason)` | 409 | `The server isn't ready yet ({r}).` |
| `serverError(code,message)` | 5xx | message, else `The server is having a moment. Try again.` |
| `offline` | — | `You're offline.` |
| `decoding(underlying)` | — | `Couldn't read the response ({u}).` |
| `other(message)` | — | message |

**Note the 401 copy deliberately does NOT claim the user was signed out** — in
dev it usually means the backend can't reach Supabase's JWKS. Keep the wording.

`conflict(reason)` carries the backend's `detail` string as a **code**
(e.g. `clip_not_ready`) that callers compare against. It is control flow, not
just display text.

---

## 6. Date/JSON contract ✅ (high-risk)

Pydantic v2 emits `datetime` as ISO-8601 with **6-digit microseconds**
(`2026-05-21T22:18:00.182734+00:00`) and `date` as `YYYY-MM-DD`.
`ISO8601DateFormatter` only handles 3 digits, so iOS uses an ordered fallback:

1. `yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXXXX` (microsecond — also the ENCODE format)
2. `yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX` (millisecond)
3. `yyyy-MM-dd'T'HH:mm:ssXXXXX` (plain)
4. `yyyy-MM-dd` (date-only)

All with `Locale("en_US_POSIX")` and `TimeZone(secondsFromGMT: 0)`.

**Android:** one custom `KSerializer<Instant>` with the same four patterns in the
same order, fixed UTC, fixed locale. Encoding must use pattern 1 so round-trips
are byte-stable.

**Separate rule:** `date_of_birth` is a Postgres `DATE`. Pydantic's date validator
**rejects** any ISO-8601 string carrying a time component. It must be serialized
as `YYYY-MM-DD` on both `CreatePatientRequest` and `UpdatePatientRequest`.
Getting this wrong 422s patient creation.

---

## 7. Data models ✅

All models mirror backend pydantic schemas. Wire format is `snake_case`;
Kotlin uses `@SerialName`. **The backend uses `extra="forbid"` on several request
schemas — sending an unknown field is a 422, not a warning.** A field was
deleted from iOS on 2026-07-28 for exactly this reason (`where_scope`).

### Decode resilience — a load-bearing pattern, not defensive noise

Multiple models decode leniently ON PURPOSE, because Supabase Realtime ships the
**raw DB row** while REST ships the API schema. They are different shapes, and
the same Kotlin class must parse both.

| Model | Lenient rule | Why |
|---|---|---|
| `Camera.SourceType` | only `"iphone"` → `.phone`; everything else → `.sensor` | `"gateway"` appeared and failed the whole camera-list decode |
| `Camera.RoomClass` | unknown → `.other` | future room classes must not break decode |
| `Camera.paired` | REST sends it; Realtime doesn't → derive from `api_key_hash ?: ingest_token_hash` | Pi cameras store creds on `ingest_token_hash`; checking only `api_key_hash` made every Pi camera flip to "PAIRING" on every Realtime UPDATE |
| `Camera.sourceType` | absent → `.phone` | pre-migration Realtime rows |
| `Camera.privacyEnabled` | absent → `false` | pre-privacy-migration rows |
| `Camera.appPresent` | absent → nil, treated as present | never falsely show "app closed" |
| `DetectionEvent.clipSuppressed` / `clipCensored` | absent → `false` | a synthesized decoder would throw `keyNotFound` and silently drop the whole event |
| `Patient.privacyModeEnabled` | optional | column is being DROPPED; a required Bool would fail every patient decode the day it disappears |
| `Patient.audioCaptureEnabled` | optional, nil ⇒ treat as **true** | facilities audio kill |
| `Detector.DoorConfig` | `doors[]`, else fold legacy single `box`/`label`/`source` into a one-element array | rows saved before multi-door shipped |

**Android:** `Json { ignoreUnknownKeys = true; explicitNulls = false }` plus custom
serializers for the derived cases. Every one of these rules must be a test.

### Model inventory

| Model | Purpose | Notable |
|---|---|---|
| `Caregiver` | authed user | `tosAcceptedAt` / `tosVersion` / `country` / `region` |
| `Patient` | person under care | `state` ∈ calm/critical/offline; `Relation` enum; `Region` US/IN; computed `avatarLetter`, `age` |
| `Camera` | one wall device per room | biggest model; `disabled` vs `aiPaused` vs `desiredState` are THREE distinct concepts (see below) |
| `Detector` | one configured detection | `AlertTrigger` 5 cases w/ 3 label variants each; `ScheduleConfig`; `DoorConfig`; `userCriteria`; `refinements` |
| `DetectionEvent` | one fired detection | `ReviewChoice`; censor flags; computed `time` + `ago` |
| `FaceEmbedding` + `FaceEnrollmentSummary` | Re-ID gallery | selfie vs harvested; status copy is **byte-identical to web** |
| `DeviceRegistration` | APNs token | **no FCM analog — see blockers** |
| `LiveKit` | token mint, talk start, active camera | `role` ∈ viewer/broadcaster |
| `ClipURL` / `PhotoURL` | short-lived signed URLs (~10 min) | never cached; refetched on every open |
| `Feedback` / `Support` | founder-facing forms | feedback takes base64 JPEG attachments |
| `Countries` | ISO alpha-2 picker source | ⏳ |

### Camera state — three orthogonal axes (easy to get wrong)

| Field | Meaning | Video flowing? | AI running? |
|---|---|---|---|
| `disabled` | user "off" toggle | yes | no (synthetic skipped row) |
| `aiPaused` | lighter pause; tile stays visible | yes | no (`skipped_reason=ai_paused`) |
| `desiredState` | `.disconnected` = phone leaves LiveKit entirely | **no** | no |

A camera can be `disabled` AND `.disconnected` at once. `isAppClosed`
(`sourceType == .phone && !online && appPresent == false`) is a FOURTH state
meaning "the caregiver must physically reopen the app; Reconnect cannot help."

`isLiveStreaming` = `livekitIdentity != nil && !disabled && desiredState == .connected`.
It deliberately does **not** gate on `online` — the SDK's track map is the source
of truth for the Live tab, because backend `online` has lied before.

`privacyEnabled` is server-derived from `roomClass` (bathroom/bedroom → true,
**locked, no user toggle**). While true: no live video/audio delivered at all,
Live tile shows a "Private room" placard, talk/call disabled, and sensitive event
clips arrive pre-blurred with audio stripped.

---

## 8. Auth flows ✅

One concrete `SupabaseAuthService` — no protocol, no base class. (The comment
explains why: SwiftUI `@Observable` does not reliably track through protocol
existentials. On Android this constraint does not exist, but there is still only
one implementation, so keep it concrete.)

| Flow | Steps |
|---|---|
| Sign up | `auth.signUp(email,password)` → **guard session != nil** (email-confirmation projects return no session → throw "Check your email to confirm your account.") → `onAuthenticated(displayName, phone, tosVersion, country)` → `POST /v1/me` → `bootstrap()` → mark new account for tutorial |
| Sign in | `auth.signIn` → `onAuthenticated(nil,…)` → `GET /v1/me` → `bootstrap()` |
| Restore | `auth.session` (throws if none) → same as sign in. Called once from `RootView.task` |
| Forgot password | `resetPasswordForEmail(redirectTo: nemara://reset-password)`. **Succeeds whether or not the email is registered** — never reveal account existence |
| Reset password | deep link `?code=` → `exchangeCodeForSession` → returns email for confirmation UI → `update(password:)` → `onAuthenticated` |
| Email change | `update(user: email)`. Until confirmed, `caregivers.email` is unchanged and the UI correctly keeps showing the old address |
| Sign out | `onWillSignOut()` (de-register push token **while session still valid**) → `signOut()` → clear caregiver → `onSignedOut()` (teardown api) |
| Delete account | `onDeleteAccount()` (`DELETE /v1/me`) → best-effort `signOut()` → teardown. **Throws if backend delete fails** so the user is not left half-deleted |

**Auth-state → Realtime token pump.** A long-lived task forwards every
`authStateChanges` event into `realtimeV2.setAuth(accessToken)`. Without it, RLS
policies evaluate `auth.uid()` as NULL and Realtime silently drops every gated
row. The SDK fires this on cold launch with the cached session, which is what
makes restore work. **This is mandatory on Android too** — supabase-kt has the
same requirement and the failure mode is silent.

Error mapping: any auth error whose description contains "invalid login" or
"invalid credentials" → `APIError.unauthorized`; everything else → `.other`.

---

## 9. Port risk register (running)

| # | Risk | Severity | Notes |
|---|---|---|---|
| R1 | FCM has no backend contract | **blocker** | `/v1/me/devices` takes `apns_token` + `bundle_id` + `environment` (sandbox\|production, a DB check constraint). FCM has one token and no client-side environment. Needs a backend decision, not a translation. |
| R2 | Face coverage geometry | high | Vision landmarks ≠ ML Kit landmarks. Pending analysis of `FaceCoverageModel.swift`. |
| R3 | Blur frame pipeline | high | Needs a custom Android `VideoSink`. Pending analysis. |
| R4 | Date fallback chain | medium | Silent, wide blast radius — half the models fail to decode if wrong. |
| R5 | `extra="forbid"` on request schemas | medium | Any extra field is a 422. Send exactly what iOS sends. |
| R6 | Realtime-vs-REST dual shape | medium | The lenient decoders above are the contract. |
| R7 | Bonjour dev config | low | Use IPs on Android. |
| R8 | Light-mode-only | low | Must pin; ignoring system dark mode. |

---

## 10. `NemaraAPIClient` — the spine ✅

One object holds BOTH the HTTP client and the observable cache. Deliberate: one
data source, one seam. Do not split it into repository + store on Android just
because that is conventional — the comment explains that the earlier project which
had that split needed it because it had four data sources.

### Observable state

```
patients:            [Patient]
camerasByPatient:    [UUID: [Camera]]
detectorsByPatient:  [UUID: [Detector]]
eventsByPatient:     [UUID: [DetectionEvent]]
lastReviewError:     String?     // dismissible banner after a failed optimistic review
isLoading:           Bool        // ONE loading affordance, not three per-screen spinners
```

**Write-through:** every mutating call updates the local collection with the row
the server returned. Realtime would also carry it; the eager write keeps the UI
snappy. Android: same, `StateFlow` updated on response.

### Three distinct refetch paths — do NOT collapse them

| | `bootstrap()` | `refresh()` | `resync()` |
|---|---|---|---|
| Trigger | sign-in / restore | pull-to-refresh | app foreground |
| `isLoading` | **yes** | **yes** | **no** (no spinner flash every foreground) |
| Opens Realtime | yes (detached) | no (channels stay attached) | no |
| Starts 1s poll | yes | no | no |
| Prunes sub-collections of deleted patients | no | **yes** | no |
| Error handling | throws | throws | **swallows per-collection (`try?`)** |
| No-op guard | — | — | returns if `patients.isEmpty` |

Merging these into one "load" is a real bug: the foreground path must not flash a
spinner, must not abort on one failed collection, and must not run before the
first bootstrap.

**Load fan-out:** `loadPatients()` first, then a parallel task group over
patients × 3 (cameras, detectors, events first page). Android: `coroutineScope` +
`async` per unit, awaited together.

### The 1-second camera state poll (the Realtime backstop)

Always on while foregrounded. Idempotent start (cancels any existing loop).

Per pass, for each patient: fetch cameras **without mutating the cache**, then diff:
- rows missing from the server → removed locally
- rows that differ → replaced
- rows not in the cache → appended
- write back **sorted by `displayId`**, and only when something actually changed

**It must mirror `handleCameraChange`'s side effects exactly** — the reconnect
window and the ever-online marking — or polling and Realtime produce different UI
for the same state change. Failures are swallowed; the next tick retries.

Android: a coroutine on the visible lifecycle, `delay(1000)`, identical diff.

### Two pieces of derived camera UI state

**`camerasEverOnline`** — persisted to `UserDefaults` under
`co.sunny.nemara.camerasEverOnline`. A camera that has been online at least once
on this install can never regress to "preparing" after a relaunch.
`isPreparing(cam)` = paired && desired==connected && !disabled && !online &&
**never** been online. An established camera that is merely offline reads as
"offline", not "preparing". **Android: DataStore, same semantics.**

**`reconnectingUntil`** — in-memory, 10-second window. Drives the Live tile's
"Connecting…" buffer so a reconnect does not read as "nothing happened". Set in
**three** places, all required:
1. `updateCamera` (optimistic, this device)
2. `handleCameraChange` (another device / web flipped it)
3. `pollCameraStateOnce` (the poll observed the flip)

Cleared the moment `online` goes true. Time-bounded so a sensor that never returns
falls through to plain "offline".

### LiveKit participant identity — a landmine

```
web    → caregiver_<uuid>_web
iOS    → caregiver_<uuid>_ios
```

LiveKit treats `identity` as a unique participant key per room. **If Android
reuses `_ios`, connecting from Android kicks the caregiver's iPhone session and
vice versa.** Android must mint `caregiver_<uuid>_android`. Room is always
`patient_<uuid>`; role is `viewer` or `broadcaster`.

### HTTP error mapping

| Status | Mapped to |
|---|---|
| 400 | `badRequest(detail ?? "Bad request.")` |
| 401 / 403 / 404 | `unauthorized` / `forbidden` / `notFound` |
| 409 | `conflict(reason: detail ?? "conflict")` |
| 422 | `badRequest("Validation failed — {loc}: {msg}; …")` |
| 5xx | `serverError(code: nil, message: detail)` |
| `URLError` notConnected / connectionLost / timedOut | `offline` |

FastAPI emits **two** error shapes and both are attempted: `{"detail": "string"}`
and `{"detail": [{loc, msg, type}]}`.

### Swallowed statuses that are CONTROL FLOW, not errors

| Call | Swallows | Means |
|---|---|---|
| `patientPhotoURL` | 404 → `nil` | no avatar uploaded |
| `clipURL` | 409 `clip_not_ready` → `nil` | show "processing…" until Realtime flips `has_clip` |
| `deletePatient` | 404 → proceed | already gone server-side; the caller's intent is still satisfied |

Getting these wrong surfaces error banners for normal states.

### Multipart (hand-rolled, no library)

- **Patient photo:** one part named `photo`, filename `avatar.jpg` / `avatar.png`.
- **Face embeddings:** N parts **all named `photos`** — the FastAPI convention for
  `list[UploadFile]`. Filenames `photo_0.jpg`, `photo_1.jpg`, …

### Realtime handlers — ordering rules

| Table | INSERT | UPDATE | DELETE |
|---|---|---|---|
| `patients` | append if absent | replace, else append | remove + **cascade-clear its cameras/detectors/events** |
| `cameras` | append if absent, **re-sort by `displayId`** | replace (+ reconnect-window check), re-sort | remove |
| `detectors` | append if absent | replace, else append | remove |
| `events` | **insert at index 0** (newest-first, matches the GET sort) | replace, else insert at 0 | remove |

`decodeEvent` **logs** on decode failure while the other three handlers swallow
silently — a deliberate canary, because a swallowed event decode means a real
alert never appears with no trace. Keep the asymmetry.

`notifyOnlineIfNeeded` fires the LiveKit re-subscribe hook when
`online == true || desiredState == .connected` — **not** on `online` alone. Gating
on `online` alone left tiles black, because `online` is written by the detector
worker's reconciler and lags. Over-calling is cheap; the hook no-ops when the
track already exists.

### Endpoint inventory (38)

Identity: `POST/GET/PATCH/DELETE /v1/me`.
Devices: `POST /v1/me/devices`, `DELETE /v1/me/devices/{token}` (**Q1**).
Support/feedback: `POST /v1/support`, `POST /v1/feedback`.
Patients: `GET/POST /v1/patients`, `PATCH/DELETE /v1/patients/{id}`,
`POST .../test-email`, `POST/DELETE .../photo`, `GET .../photo-url`.
Face: `POST/GET /v1/patients/{id}/face-embeddings`, `GET .../summary`,
`DELETE /v1/face-embeddings/{id}`.
Cameras: `GET /v1/patients/{id}/cameras`, `POST .../cameras/pairing-code`,
`POST /v1/cameras/{id}/pairing-code`, `GET /v1/cameras/discovered`,
`POST .../cameras/pi-provision`, `PATCH/DELETE /v1/cameras/{id}`,
`POST /v1/cameras/{id}/unpair`, `GET/PUT /v1/cameras/{id}/detectors`.
Detectors: `GET/POST /v1/patients/{id}/detectors`, `POST /v1/detectors/infer`,
`PATCH/DELETE /v1/detectors/{id}`, `POST /v1/detectors/{id}/refine`
(**with a `preview` dry-run flag that must not overwrite local state**),
`GET .../detectors/library`, `POST .../detectors/from-library`.
Doors: `POST /v1/detect-doors`, `GET /v1/patients/{id}/wandering-doors`,
`POST /v1/cameras/{id}/wandering-door`.
Events: `GET /v1/patients/{id}/events` (cursor-paged, `filter` query),
`GET /v1/events/{id}`, `GET /v1/events/{id}/clip-url`, `PATCH /v1/events/{id}`.
LiveKit/talk: `POST /v1/livekit/token`, `GET /v1/patients/{id}/active-camera`,
`POST .../talk/start`, `POST .../talk/end`.
Dev-only: `POST .../cameras/{cid}/record-clip` (gated by `enableClipCapture`,
hard-false in release).

---

## 11. `LiveKitClient` — live video + audio ✅

One `Room` instance for the app's lifetime, reused across patients (the caregiver
views one room at a time; opening another patient disconnects the previous first).
Tracks are keyed by participant identity = the camera's `livekit_identity`.

### `CameraConnectivity` — the fused predicate

Five states: `unpaired`, `roomDown`, `offline`, `joining`, `live`. **All UI must
switch on this**, never on the raw signals, because room state, backend
`cam.online`, SDK track presence, and placeholder rows disagree during reconnects.

`connectivity(of:)` deliberately does **not** consult `cam.online` — the SDK's
`videoTracksByIdentity` map is the most direct "video is flowing right now"
signal, and the backend column can lag it by ~10s (the REST reconcile interval).

> Observation: `.joining` is declared but `connectivity(of:)` never returns it —
> the function yields `unpaired` / `roomDown` / `live` / `offline` only. Port the
> enum as-is (parity), and do not invent a `joining` branch.

### Audio is volume-gated, NOT subscription-gated (do not "fix" this)

Tracks stay subscribed and continuously decoding; audibility is set by writing
`track.volume = 0.0 | 1.0`.

The comment records why: toggling `publication.set(enabled:)` churns the SDK's
audio engine in a way the per-buffer renderer never recovers from — the waveform
stays flat forever after re-enabling. Continuous decode costs ~20kbps per camera
and that trade was made deliberately.

**Android port question:** confirm `livekit-android` exposes an equivalent
per-track volume control. If it does not, this needs a design decision, not a
substitution — see `QUESTIONS.md` Q3.

**Audibility rule:** `audioEnabledByIdentity.contains(id) || id == activeCallIdentity`.
Talk wins, and it *overlays* rather than replaces — the caregiver's per-tile mute
choices survive a call starting and ending.

**`revokeAudio(forIdentity:)`** (called when per-camera privacy flips on) must
remove the identity from the set, not merely zero the volume: `handleSubscribedAudio`
restores volume *from the set*, so a stale entry re-arms playback on the next
resubscribe. Zeroing alone is a privacy hole.

**Per-tile mute state is connection-scoped** — cleared on `disconnect()`, so a
fresh patient session starts with every tile muted.

### The audio-level taps (two of them, combined, not per-track)

Per-track `RemoteAudioTrack.add(audioRenderer:)` was **dead on real devices**:
subscribed-track fired four times across camera reconnects and render never fired
once, likely the multicast adapter's `NSHashTable.weakObjects()` going stale
across resubscribe thrash. The working path is two combined taps registered on
`AudioManager.shared`: one remote (post-mix, pre-playback) and one local (the
caregiver's outgoing mic).

The waveform appends `max(lastLocalPeak, lastRemotePeak)` so it tracks whoever is
louder rather than two callbacks combing against each other. Both reset to 0 when
a call ends. Ring buffer capacity **80 samples**, pushed 20-50×/sec. Levels only
accumulate when `activeCallIdentity != nil`.

**The Int16 bug — port the fix, not the original.** The LiveKit APM hands
renderers **Int16, non-interleaved** PCM. Reading `floatChannelData` on an Int16
buffer returns nil, so an earlier `guard let … floatChannelData` bailed on every
single buffer and the waveform sat dead flat while audio flowed fine both ways.
The renderer must handle **both** layouts, and must widen to `Int32` before `abs`
(`abs(Int16.min)` overflows `Int16`). Normalize by `Int16.max`; mix all channels
into one peak so a stereo track does not visually double itself.

### Mic publish forces the loudspeaker

Publishing the mic flips the SDK's `AVAudioSession` to `.playAndRecord/.voiceChat`,
which defaults output to the **earpiece**. iOS then forces
`overrideOutputAudioPort(.speaker)` so the caregiver hears the patient across the
room instead of holding the phone to their ear.

**Android needs the equivalent** — `AudioManager` mode + speakerphone routing, or
the LiveKit Android audio-switch handler. Without it the Android app will appear
to "lose audio" on Talk, which is a wrong-diagnosis magnet.

### `ensureSubscribed` — 4-step escalation, idempotent

1. Track already present → no-op.
2. Room not connected → bail (connect-time backfill will handle it).
3. Participant present but tracks missing → walk publications,
   `set(subscribed: true)` to re-issue the subscribe RPC.
4. Participant absent entirely → **only if `reconnectIfAbsent`**, force a room
   disconnect (true SDK desync).

`reconnectIfAbsent` defaults true but `AppEnvironment` passes **false** from the
Realtime hook, because that path over-calls while a publisher may simply not have
rejoined yet, and tearing the room down there strands the app (nothing re-mints a
token outside the view's bootstrap).

**Three delegate hooks all call it:** `didSubscribeTrack`, `didPublishTrack`, and
`participantDidConnect` — because with autoSubscribe the SDK can still miss a
publisher who joins *after* we connected. That was the intermittent "fresh
camera-on doesn't show" bug. Keep all three.

### Connection-state rule

On a `disconnected` delegate callback, **do not clobber a terminal `.failed`**
state — the failure reason must stay visible in the UI.

---

## 12. Video rendering, blur, and frame capture ✅

### `PeopleBlurStore` — device-local, per-patient

Key `peopleBlur.enabledPatientIDs`, a `Set<String>` of patient UUIDs in
`UserDefaults`. **Deliberately NOT server-backed:** it is a viewing preference for
this one phone, changes nothing about recording or insights or what other devices
see, and is fully reversible. Mirrors the web dashboard's per-patient
`localStorage` toggle. Android: DataStore, same key, same semantics.

### The blur pipeline — boxes, not segmentation

Pipeline: attach a renderer to the track → per frame, run human **detection** to
get person bounding **boxes** → Gaussian-blur each box over the sharp frame →
render to a layer.

**Why boxes and not segmentation** (the comment is explicit): a detector works at
any distance, near and far. Person *segmentation* is tuned for close-up subjects
and **drops a person who walks away** — which for a privacy feature is the exact
wrong failure direction.

**This is DISPLAY privacy only.** Frames are still decoded on the device.

### Cross-platform constants — these are shared with web, not iOS-specific

The Swift comments mark each of these as "mirrors the web dashboard's X", which
means they are the product's constants, not Apple tuning. Port them exactly:

| Constant | Value | Note |
|---|---|---|
| Box padding | `0.18` | web `PAD` — blur sits proud of the body |
| Blur strength | `36` | web `BLUR_PX`; CSS blur radius ≈ Gaussian sigma |
| Privacy hold | `1.5s` | web `HOLD_MS = 1500` — a detection gap must never suddenly un-blur someone |
| Min confidence | `0.25` | lower catches farther/partial people at the cost of false positives |
| Detection cadence | ~15/s (`0.066s`) | detection is throttled; **the blur composite runs every frame** |

The hold-box behavior is a privacy guarantee, not a smoothing nicety. Boxes are
held with an expiry and only allowed to lapse when detection returns empty *and*
the hold has expired.

### Q3 is largely ANSWERED by this reading

iOS uses Apple Vision's `VNDetectHumanRectanglesRequest`. The **web dashboard uses
COCO-SSD**, and iOS is explicitly described as mirroring it. So the Android
implementation should follow **web's approach, not iOS's**: an SSD/MobileNet-class
person detector via MediaPipe or TFLite, which gives the same box semantics that
the shared constants above were tuned against.

Remaining Android specifics:
- Renderer attach point: a custom `VideoSink` on the remote video track.
- Blur composite: RenderScript is deprecated; use `RenderEffect` (API 31+) or a
  GPU/`Bitmap` path that works at `minSdk 26`. **This is the one real open
  decision** — see `QUESTIONS.md` Q3.
- Frame orientation: iOS maps LiveKit's clockwise-degrees `rotation` to an
  EXIF-style orientation. Android needs the analogous mapping; getting it wrong
  yields sideways or upside-down video.
- Model warm-up at launch (iOS calls `BlurVideoRenderer.warmUp()` from the app
  root) so the first blurred frame is not slow.

### The render watchdog — port the INTENT, verify the mechanism

`LiveKitVideoView` carries a watchdog for a specific failure: **iOS can reclaim
the Metal renderer while the app is backgrounded, while the LiveKit room and the
subscribed track stay perfectly healthy.** On foreground, SwiftUI does not re-bind
because the track object is unchanged, so the dead renderer is never rebuilt. The
tile reads "LIVE" and paints black, and no other recovery path fires because every
other path is gated on the track being *absent* — here it is present. The web
client renders the same camera fine, so it is purely client-local.

Two mechanisms, both needed:

1. **Poll `isRendering` every 0.5s.** Repair after **2s** of continuous black,
   with a **5s** cooldown. Guarded by `everRendered` so it only ever repairs a
   tile that went black *after* going live — it never fights the first-frame
   handshake, and never fires on a healthy tile.
2. **Unconditional re-attach on every foreground**, after a 0.4s settle delay.
   Because `isRendering` can report **true** while the surface is dead, the
   detector cannot see that state at all. Rather than detect it, iOS rebuilds on
   the known trigger. Cost: a brief black→video flicker each foreground, accepted.

**Android note:** the underlying cause is Apple-specific, but Android
`SurfaceViewRenderer` has its own surface-lifecycle teardown on background. Do not
assume it is unnecessary and do not port it blindly — implement the same intent
(recover a live-but-black tile, never disturb a healthy one, never fight the first
frame) against Android's actual surface lifecycle, and record the mechanism in
`DIFFERENCES.md`.

Repair is `track = nil; track = track` — force a renderer rebuild against the same
track. `layoutMode = .fill` and `mirrorMode = .off`: fill rather than fit is
deliberate, because the wall phone publishes portrait and the caregiver app renders
landscape, so fitting would letterbox away half the tile.

### `LiveKitFrameGrabber` — one-shot still capture

Used by the wandering exit-door picker to send the current view to the
door-detection VLM. Attaches a renderer, takes the **first** frame, encodes JPEG
**on the SDK's video thread** (only the `Sendable` bytes cross back), resumes the
awaiting continuation, detaches.

Defaults: **4s timeout, maxWidth 1280, quality 0.85.** Timeout and capture race in
a task group; the loser is cancelled. `cancel()` resumes the continuation with nil
so a timed-out caller cannot leak a suspended task.

---

## 13. Design system ✅ (tokens; components in progress)

### Color — 22 tokens, extracted from the asset catalog

Source of truth on iOS is the asset catalog, **not** Swift, because iOS launch
screens can only reference catalog colors. Android has no such constraint: define
once in Kotlin. Light-mode only, no dark variants.

| Token | Hex | Role |
|---|---|---|
| `Paper` | `#EFE6DA` | app background (warm cream) |
| `PaperDim` | `#E6DDC8` | inset / recessed surface |
| `Card` | `#FBF8F0` | raised card |
| `CardAlt` | `#F0E8D6` | alt card surface |
| `Ink` | `#1C1A14` | primary text |
| `Ink2` | `#2E2A20` | secondary text |
| `Ink3` | `#4A4539` | tertiary text |
| `Ink4` | `#8A8373` | quaternary text |
| `Line` | `#C9C0AB` | hairline border |
| `Terra` | `#A04331` | brand primary (rust) |
| `TerraSoft` | `#F2DDD3` | safety tint |
| `TerraDeep` | `#7A2F1F` | pressed / deep brand |
| `TerraInk` | `#FBF8F0` | text on terra |
| `Action` | `#1C1A14` | primary CTA (warm near-black) |
| `ActionSoft` | `#E6DDC8` | soft action surface |
| `ActionInk` | `#FBF8F0` | text on action |
| `Sky` | `#6B8AA0` | routine-event palette |
| `SkySoft` | `#DDE6EE` | routine tint |
| `Success` | `#22C55E` | success |
| `Warning` | `#F59E0B` | warning |
| `Danger` | `#A04331` | = Terra |
| `Info` | `#6B8AA0` | = Sky |

Note `Action == Ink`, `ActionInk == TerraInk == Card`, `ActionSoft == PaperDim`,
`Danger == Terra`, `Info == Sky`. Keep them as **separate named tokens** even
though the values collide — the semantic names are used at call sites.

### Typography — 3 families, 20 named styles

Fraunces 144pt **Soft** (serif), Inter 18pt (sans), JetBrains Mono. Fraunces ships
as a **pre-baked static cut** with the SOFT=75 / opsz=144 axes pinned, to avoid the
multi-megabyte variable font. Fraunces has **no Bold cut** — bold requests round
down to SemiBold. Android: put the same `.ttf` files in `res/font` and build three
`FontFamily`s with the same weight-rounding behavior.

| Group | Styles |
|---|---|
| Serif | `display` 44, `largeTitle` 32, `title1` 28, `title2` 22, `title3` 18, `eventTitle` 17/semibold |
| Sans | `bodyLarge` 16, `body` 14, `bodyMedium` 14/500, `bodySemibold` 14/600, `bodySmall` 13, `bodySmallStrong` 13/600, `labelStrong` 12/600, `label` 12/500, `caption` 11, `captionStrong` 11/600 |
| Mono | `eyebrow` 11/600, `monoSmall` 10/600, `monoMicro` 9/700, `monoValue` 12/600 |

Kerning is applied per-site (e.g. `-0.8` on `title1`, `-0.3`/`-0.4` on serif rows)
and letter-tracking on mono labels (`0.4`–`0.7`). Carry these; they are visible.

### Geometry

Spacing `4, 8, 12, 16, 20, 24, 32, 40, 56` (xxs → huge).
Radius `sm 6` (chips), `md 10` (fields, buttons), `lg 14` (cards), `xl 20`,
`pill 999`.

### `NemaraButton` — 5 variants × 3 sizes

`primary` (action bg / actionInk fg), `terra`, `secondary` (card bg / line border),
`ghost` (transparent), `danger` (terra). Sizes drive font 13/14/16, vertical pad
8/12/16, horizontal pad 12/16/20. Radius `md`. Optional `block` (fill width) and
`systemImage` (icon at fontSize+2).

**Every button logs analytics.** `Analytics.logButton(track ?? title)` fires before
the action, synchronously. The optional `track` label exists so analytics survive
copy changes and disambiguate buttons sharing a title. **This is the single
instrumentation point for the whole app** — replicate it in the Android component,
not at call sites.

`NemaraCard`: 6 tones (default/alt/terra/sky/success/action), radius `lg`, 1px
border defaulting to `Line`, overridable.

---

## 14. Analytics ✅

Custom, Supabase-backed. Not Firebase, not PostHog. Table `ui_events`; the `Event`
struct's field names are **snake_case to match columns exactly** because PostgREST
maps JSON keys straight to columns.

**The design contract, stated in the source: zero logic changes.** `log*` is a
synchronous, non-throwing, side-effect-only buffer append. It never wraps, blocks,
throws, or awaits. If analytics breaks entirely the app behaves identically.
**Port this property, not just the feature.**

| Setting | Value |
|---|---|
| Master kill switch | `AnalyticsConfig.enabled` — one line disables everything |
| Flush interval | 3600s (hourly) |
| Persist to disk every | 25 events |
| Max buffered | 5000 (oldest dropped) |
| Insert batch size | 200 |

Four event types: `button`, `screen`, `toggle`, `lifecycle`. Each row carries a
client-generated UUID (idempotent upsert key), session id (new per launch),
monotonic `seq` (exact ordering on timestamp ties), current screen, app build, OS
version, device model, and optional string metadata.

Behaviors to preserve:
- **Only the currently-authed caregiver's events are flushed** — RLS rejects
  others. Everything else stays buffered.
- Flush uses `upsert(onConflict: "id", ignoreDuplicates: true)` so a retry after a
  dropped response is idempotent instead of failing forever on a PK collision.
- Flush **stops at the first failed chunk** and keeps everything not confirmed sent.
- Buffer persists to disk (`ui_events_buffer.json`) and is reloaded next launch, so
  a crash or kill does not lose events.
- `persistAndFlush()` on background.

Android: DataStore/file for the buffer, `supabase-kt` postgrest for the upsert,
same table and column names.

---

## 15. Roster, events, and the patient shell ✅

### Roster (Home tab)

Greeting is time-based: `<12` "Good morning", `<17` "Good afternoon", else "Good
evening". Title "Today". Empty roster defers to `EmptyRosterView`.
Pull-to-refresh calls `api.refresh()` — the escape hatch for a Realtime-dropped
row change.

**`PatientRow` derives everything from local events, not from server fields.**
`patient.state` / `statusLine` / `lastEventAt` are *not* server-maintained yet, so
the row computes from `eventsByPatient`:
- status text = "All quiet" when no unopened events, else "N New event(s)"
- **only an unopened SAFETY event** escalates the card to the terra attention
  treatment (tint + 1.5px terra border + pulse dot). Routine events show the count
  in sky but never paint the card red.
- `isPaused` is **pinned to `false`** — the patient-level `cameras_paused` flag no
  longer gates anything, and the row deliberately stopped showing a misleading
  "Monitoring paused" treatment. Port the pinned-false branch as-is.

### `PatientShell` — five tabs

Live / Events / Sensors / Detectors / Settings, presented as a full-screen cover
over the Home tab bar so the two bars never stack. Pinned `PHeader` at the top of
each tab (not part of the scroll), with the Settings tab passing
`showsIdentity: false` because its profile card already shows identity.

**The single LiveKit teardown point** is `PatientShell.onDisappear` — it fires when
the whole patient cover is dismissed, **not** on inner sub-tab switches. So the
room survives Live ↔ Events ↔ Settings and only disconnects when the caregiver
backs out of the patient. Getting this wrong reconnects the room on every tab tap.

First-open data-collection consent auto-presents exactly once, gated on
`dataCollectionDecidedAt == nil`. Both choices stamp `decided_at` server-side, so
it never re-nags.

Deep link: when the router targets this patient with a `focusEventId`, jump to the
Events tab (`initial: true` covers the cold-launch case where the value is already
set when the shell first appears).

### Events tab

Filters All / Unreviewed / Reviewed with live counts; **default filter is
Unreviewed**. Header pill reads "Last 7d".

- **A 20-second quiet poll runs while the tab is on screen**, because Realtime has
  no replay and a missed INSERT would otherwise stay invisible until relaunch.
- **The poll pauses while a detail sheet is open** (`openedEvent == nil` guard).
  Refetching replaces `eventsByPatient`, which re-renders the open sheet and
  historically **killed clip playback mid-watch**. This guard is load-bearing.
- Tapping an unopened event fires `PATCH {opened: true}` before presenting.
- Empty-state copy differs per filter ("All caught up" vs "Nothing to see here
  yet" vs "Nothing reviewed yet").
- `lastReviewError` renders a dismissible terra banner at the top.

### Event card / detail / review

`EventCard`: background encodes category (terraSoft = safety, skySoft = routine),
**opacity encodes state** (0.62 opened, 1.0 unopened), title weight also shifts
(regular when opened, semibold when not). CUSTOM pill only when `source == custom`.
REVIEWED pill replaces the chevron.

The relative-time label is wrapped in a 60s ticking view because `ago` reads the
wall clock, which is not a reactive dependency — without it the label freezes at
first render. Deliberately scoped to the leaf label so it cannot re-trigger the
refetch that killed clip playback.

`EventDetailView` (step 1 of 2): polls `clipURL` **every 3s** until ready, because
archival runs ~25-45s after the event fires. Five clip states: pending, generating,
notReady, failed, loaded.
- The `AVPlayer` is created **once** when the URL resolves and held in state. An
  inline player was rebuilt on every re-render and the clip "closed" mid-watch at
  ~20s. Android: hold the ExoPlayer instance, do not construct it in composition.
- `clipSuppressed` is **terminal** — no polling, show the "No clip for this event"
  placard. Copy is deliberately reason-neutral: it must not claim privacy (private
  rooms *do* get clips now) and must not hide failures.
- `clipCensored` shows a **dignity cover** over the video requiring a deliberate
  "View video" tap. Per-open, **never persisted** — dignity-first default every
  time. Do not add a "don't ask again".

`EventReviewView` (step 2 of 2): three choices (correct / wrong / unsure) with
category-dependent sub-copy, plus optional notes.
**Submit is optimistic:** write reviewed state into the local cache, dismiss
immediately, then PATCH in the background. On failure, roll the cache back to the
snapshot and publish `lastReviewError`. Snappy close without hiding failures.

---

## 16. Auth screens + the legal gate ✅

### Password policy (SignUp)

All five must pass, shown as a live checklist:
`>= 8 chars`, one uppercase, one lowercase, one digit, one non-alphanumeric.

**Phone is required**, not optional — "so we can text the caregiver back from
support". Validation: `>= 10 digits` after stripping non-digits.

`canSubmit` = non-empty name AND email contains `@` AND phone valid AND password
valid AND passwords match AND **terms agreed** AND not already authenticating.

Signup collects: name, email, phone, **country picker**, password, confirm.
`tosVersion` is sent as `LegalConstants.currentTosVersion`.

### Login

Email + password, a "Keep me signed in" checkbox **defaulting to true**, a
Show/Hide password toggle, and Forgot password. `canSubmit` = email contains `@`
AND password non-empty AND not authenticating.

Both screens disable the CTA and drop it to `opacity 0.5` when invalid, and swap
the button label while in flight ("Creating account…" / "Signing in…").

### The legal gate — no accept-without-reading path

`TermsConsentView` is a sheet with a segmented picker over **two** documents
(Terms of Service, Privacy Notice), each a bundled PDF.

- **"I agree" unlocks only after the reader scrolls to the end of BOTH.** A
  checkmark appears next to each reviewed document's tab label.
- Every document stays **mounted** (opacity-hidden when not selected) so each
  remembers its own scroll position and reviewed state.
- Cancel returns without agreeing, leaving signup blocked.
- Helper line while incomplete: "Scroll to the end of each document to continue."

`LegalConstants.currentTosVersion = "2026-07-26-placeholder"` and **must stay in
sync with the web app's `CURRENT_TOS_VERSION` and the backend's
`KNOWN_TOS_VERSIONS` allowlist — the server rejects any other value.**

Android: same two PDFs, same version string, same scroll-to-end gate. A scroll
listener that fires at the last page, per document, is the mechanism.

---

## 17. Detector creation + the detail editor ✅

These two screens carry the most product logic in the app.

### `AddDetectorView` — two stages

**Stage 1 (entry):** name, optional note, and a plain-English prompt.
**Stage 2 (review):** the LLM-inferred fields, all editable, then Create.

`POST /v1/detectors/infer` returns alert trigger, sustained flag, duration,
episode gap, category, icon, sensitivity, schedule, and a **plain-language `plan`
preview** rendered as Watches / Alerts / Checks (+ "Also listens, not just
watches" when `plan.listens`). Nil plan means preview composition failed
server-side — hide the card.

- **Icons are shown with human labels, never raw SF Symbol names.** A curated
  20-icon set maps `figure.walk.motion` → "Walking", `door.left.hand.open` →
  "Door", etc. Anything inferred outside the set falls back to a prettifier that
  strips `figure.`/`person.`/`.fill` and title-cases. Android must ship an
  equivalent icon set **and the same label map** — the underlying glyph names are
  iOS-specific but the caregiver-facing labels are product copy.
- **`schedule_config` is NOT sent (dissolved 2026-07-18).** `when_scope` is the
  windows mechanism. Absence detectors serialize their pickers into scope text
  like `MON,WED 8:00 AM - 9:00 AM`, which the backend parses directly. The
  formatter uses a **fixed `en_US_POSIX` locale** because the backend grammar
  requires AM/PM tokens regardless of the device's 24-hour setting. Non-absence
  detectors send `ALWAYS`.
- **Create-as-draft:** `isOn: false` on create. The new detector opens straight
  into the detail sheet so the caregiver reviews compiled criteria before turning
  it on. Dismissing that sheet dismisses the whole flow.
- `durationSeconds` only sent when trigger is `.duration`; `episodeGapSeconds`
  only when `isSustained`.

### `DetectorDetailSheet` — the unified editor

Reads top to bottom as: what you asked for → what Memoria looks for → refine →
how it alerts → category → sensitivity → where it runs → when it runs → Save.

**The refine box is a dry run.** "Re-generate" calls
`POST /v1/detectors/{id}/refine` with `preview: true`, which compiles *with* the
feedback and returns the resulting criteria **without persisting**. The card shows
a `PREVIEW · NOT SAVED` pill. Nothing commits until Save.

Critically: **the backend re-infers alert trigger, active window, and rooms from
the change**, so the staged pickers are re-synced from the preview response
(`syncStagedControls`) — and again after a committed save. Skip this and the
pickers silently go stale relative to the criteria.

Save is a two-step sequence:
1. One `PATCH` carrying only changed fields (rooms, when, category, sensitivity,
   alert timing). Timing changes need **no recompile** — the detection fragment is
   unchanged, only when it fires.
2. If custom and feedback is pending, `refine(preview: false)` — which recompiles
   server-side. Then **poll up to 3 times at 4s** for a row with a newer
   `updatedAt` so the fresh summary lands without user action.

Other required behaviors:
- A freshly-created detector arrives with `identifier == nil` while the compile
  runs. `pollWhileCompiling` polls **15 times at 1.5s** until it lands.
- `isDirty` gates Save across seven independent conditions; `timingInvalid`
  (duration trigger with no duration set) blocks save entirely.
- Room selection: "All rooms" chip plus per-room chips built from the patient's
  distinct camera rooms. **Deselecting the last room snaps back to All rooms** —
  an empty selection is meaningless. Saved as `["ALL ROOMS"]` or the explicit list.
- When-scope parses/serializes `HH:MM-HH:MM`, defaulting to 08:00-20:00 when the
  stored value is `ALWAYS` or unparseable.
- Duration input is in **minutes** in the UI, clamped 1-1440, stored as seconds.
- The criteria card is the app's one AI surface: terra outline + soft glow, and a
  shimmer skeleton with three staggered bars while generating, not a bare spinner.
- Sensitivity is editable for **any** detector regardless of on/off state, with an
  info sheet explaining the confidence-vs-noise trade-off.

---

## 18. `PatientLiveView` — the call metaphor (partial ✅)

Carousel of camera tiles; a call button on the current tile opens full-duplex
audio with **that** camera. Swiping during an active call **transfers** the call.

**Connection lifecycle:** connect on patient open (`bootstrap`), disconnect on
patient close (`PatientShell.onDisappear`). Sub-tab switches keep the room alive;
`bootstrap` short-circuits when the room is already up for this patient. Without
that short-circuit every Live tab re-entry triggered a full reconnect.

**Connect with BROADCASTER grants up front**, even for plain viewing. The mic is
never published until the caregiver taps Call, but holding the publish grant makes
call start/end a **mic toggle with no room reconnect**, so video tiles never blink.
The wall phone still won't play caregiver audio outside a call — it gates on the
backend's `set_talk_active` packet, not on the grant.

**Transfers are atomic:** `/talk/start` with a new `cameraId` broadcasts
`is_active=true` to the new target and `false` to every sibling, so no `/end` is
needed between transfers. A `transferSeq` counter drops stale responses from rapid
swipes.

**Swiping to an ineligible camera hangs up rather than transferring** — a
gateway/Pi camera has no speaker, and a privacy-enabled camera has talk disabled.
Transferring there would strand an active call with no way to end it.

**Privacy interlocks (three separate ones, all required):**
1. Privacy flipping on for the call target tears the call down, including from
   `.connecting` — a flip landing mid-`startCall` must not complete against a
   private room.
2. Every private camera identity gets `revokeAudio` immediately, with
   `initial: true` so cameras that flipped private while the view was away are
   swept too (the room persists across sub-tabs).
3. The full-screen view shows a privacy placard instead of video.

**The desync reconciler.** A camera can be backend-online and
intended-connected while this device has no track. `desyncSince` records when that
state began; after >2s a surgical re-subscribe is issued, rate-limited per identity
by `lastResubscribe`. Suppressed while the reconnect-all loop owns recovery.

**Reconnect-all is a loop, not a one-shot.** On `camerasPaused` true→false it runs
up to **10 passes at 2.5s (~25s)**, REST-refetching camera rows each pass rather
than trusting a Realtime echo, and forcing a clean LiveKit rejoin on **even**
passes. The reason: the Swift SDK can drop `track_subscribed` across a publisher
leave/rejoin and will not re-fire it, so the only reliable recovery is a fresh
`connect()` that re-runs autoSubscribe against the current participant list. Pass 0
usually fires before the sensor has republished; later passes catch it. Bails the
moment a camera is live.

**Full-screen is rotated manually.** The app is portrait-locked but the wall phone
publishes landscape 1280x720, so the view rotates 90° with width/height swapped and
the caregiver physically turns the phone. Chrome rotates with the video.

Device-local state: `sensitiveRoomAcknowledgedCameraIds` in `UserDefaults` — the
one-time "private room" explainer dismissal, per camera. The privacy state itself
is server-derived; only the acknowledgement is local.

`talkEnabled` is a compile-time kill switch that hides the Call button without
removing call code.

---

## 19. Sensor onboarding wizard ✅

Three linear steps for bringing a Raspberry-Pi "Memoria Sensor" onto the home
network. Presented as a sheet with **no Cancel** — swipe down to dismiss.

1. **setUp** — plug-in instructions, a 6-character setup code (normalized to
   uppercase alphanumeric, must be exactly 6), then "Join sensor Wi-Fi" joining
   SSID `Memoria-Setup-<CODE>`. An error path offers manual Settings instructions
   plus "Try again" / "Continue".
2. **joining** — a **3s discovery poll** against `GET /v1/cameras/discovered`
   with a **120s budget**. Exactly one freshly-seen sensor auto-advances; more
   than one shows a picker rather than guessing. An always-available "Go back"
   resets to step 1.
3. **configured** — `AssignSensorForm`: room name + icon (6 options: tv, fork.knife,
   bed.double, shower, door, car) → `pi-provision`, then a success confirmation
   whose Done dismisses. Copy: "…is now paired. It will come online shortly",
   because the row reads `online=false` until the Pi opens its outbound stream.

Step indicator: "STEP n OF 3" plus three capsule bars, filled terra up to the
current step.

**Step 1's join mechanism does not exist on Android.** See `QUESTIONS.md` Q5 and
`DIFFERENCES.md` D6. Everything else here ports unchanged.

---

## 20. Face enrollment coverage model ✅

`FaceCoverageModel` is **pure logic with no camera, UI, or Vision dependency** —
the camera calls `ingest(...)` and the dials render `segments` / `needle*` /
`guidance`. It therefore ports to Kotlin essentially unchanged.

### The dial

Head pose maps to a point: `x = yaw` (turning right positive), `y = pitch`
(looking up positive). `angle = atan2(y, x)`, `magnitude = hypot(x, y)` in degrees.
The dial is **12 equal arcs**. An arc is covered once a good-quality, well-framed
frame lands in it **with magnitude ≥ 22°** — so a token tilt cannot green an arc.
A separate frontal capture (magnitude < 10°) is required. Per arc the **2 most
extreme** frames above the quality floor are kept, replacing the least-extreme
when a steeper one arrives.

| Parameter | Value |
|---|---|
| `segmentCount` | 12 |
| `frontalMaxDeg` | 10 |
| `extremeMinDeg` | 22 |
| `reachTargetDeg` | 45 (Vision yaw degrades near true profile ~±70°) |
| `minQuality` (frontal) | 0.4 |
| `minQualityFloor` (full extreme) | 0.25 |
| `framesPerSegment` | 2 |
| `frontalTarget` | 2 |
| `guidanceDwell` | 0.3s |
| `tooFarHeight` | 0.28 of frame height |
| `darkLuma` | 0.28 |

`isComplete` = frontal captured AND all 12 arcs covered.
`isSubmittable` = frontal AND ≥ 50% of arcs.
Upload order: frontal frames first, then each covered arc's frames.

### Three design decisions that must survive the port

**1. Capture reads the RAW pose; only the needle is smoothed.** An earlier version
smoothed the capture signal and it "just made it feel slow" — a held angle must
register instantly. The needle gets its own exponential smoothing (α = 0.4) purely
so the pointer glides. Smoothing the gate is the mistake; smoothing the drawing is
correct.

**2. The quality floor is a RAMP, not a threshold.** Strict 0.4 when frontal,
descending linearly to 0.25 at `reachTargetDeg`. Reason: capture quality is
intrinsically lower for turned faces, so a flat high bar would reject every profile
even in perfect light — and would make a "get better light" message a lie.

**3. Guidance is debounced by dwell time, on MONOTONIC uptime, not wall clock.**
A new caption state must persist 0.3s before committing, so text never strobes on
pose noise. Using `Date()` would let an NTP jump mid-enrollment freeze or strobe
the caption. Completion snaps immediately, bypassing the dwell.

### Guidance states and copy

Seven states: `searching`, `tooFar`, `tooDark`, `holdStill`, `active`,
`frontalNeeded`, `done`. Only the stuck states speak; `active` and `done` render
no caption because the needle carries the guidance.

The low-quality states are **actionable** — they name the likely fixable cause
rather than a bare "hold still", chosen by checking face height first, then scene
luma, then falling through to motion blur. This must also cover the frontal frame:
in a dim room the frontal capture can't clear the strict 0.4 floor, and without
this branch enrollment silently stalls on "look straight ahead to finish" with no
reason given.

Copy varies by `GuidanceStyle` (front self-enroll vs rear nurse-orbits):

| State | subjectTurns | cameraOrbits |
|---|---|---|
| searching | "Re-center your face on the inner circle" | "Point the camera at their face" |
| tooFar | "Move a little closer" | "Move a little closer to them" |
| tooDark | "Find brighter light" | "Move them into better light" |
| holdStill | "Hold still" | "Hold still" |
| frontalNeeded | "Now look straight ahead to finish" | "Now face them head-on to finish" |

### Android mapping

`yawDeg` → `headEulerAngleY`, `pitchDeg` → `headEulerAngleX`, and
`brightness` / `faceHeight` / `framed` are computed from the frame and bounding box
exactly as iOS does. **`quality` has no ML Kit equivalent** and is the one open
item — see `QUESTIONS.md` Q2. The 0.4 / 0.25 floors are calibrated to Vision's
scale and must be re-derived against whatever metric replaces it.

---

## 21. Camera detail ✅

Three control surfaces, plus conditional sections.

| Control | Field | Effect |
|---|---|---|
| Pause AI / Resume AI | `ai_paused` | instant; video keeps streaming, only the LLM tick is skipped |
| Disconnect / Reconnect | `desired_state` | wall phone polls every 1s and leaves/rejoins LiveKit, ~1-2s |
| Per-camera detector toggles | `cameras_detectors` | `PUT /v1/cameras/{id}/detectors` with the **full new set** |

**"Hide / Show" (`disabled`) was removed from the UI** to keep the options simple.
The field still exists and is honored where read — it is just no longer
user-settable. Do not re-expose it.

Behaviors to preserve:
- Disconnect is **confirmed via alert** (destructive direction); Reconnect is not.
- The busy flag clears in `defer`, **not only in `catch`**. The comment notes they
  were bitten by exactly this in the web grid: on a successful round-trip the
  button stayed permanently disabled because the row stays mounted across the
  state change.
- Detector toggles are optimistic with rollback on error, with a per-row in-flight
  disabled state keyed by detector id.
- **Wandering is excluded from the plain toggle list** — it is controlled by the
  exit-doors section (only active once doors are set), so a plain toggle would be
  a dead switch.
- "Advanced" (holding Pause AI) is **collapsed by default** — a niche lever that
  sat too prominently next to Disconnect.
- Pairing-code section shows only for `sourceType == .phone && !paired`. Sensor
  (Pi) cameras get their ingest token at provision time, so a 6-digit code would
  be misleading there.
- Unpair (phone + paired only) returns the camera to UNPAIRED **without deleting**
  — room, detectors, and history are kept, re-pairable with a fresh code. Distinct
  from Delete.
- The AI-paused chip shows only when the camera is otherwise visible (not
  disabled, not disconnected) — otherwise the badge would mislead.
- Detail line: `SENSOR %02d · <livekit_identity or "no identity">`.

---

## 22. Detection library ✅

Two modes off one screen.

**`.add` (Detectors tab):** already-added entries render **locked** with an
"ADDED" pill and cannot be re-added. Confirm POSTs only the new keys. Footer reads
"Add N detector(s)" and is disabled at zero new picks.

**`.onboarding` (right after patient creation):** the recommended + already-seeded
set is **pre-checked**, and Continue performs a full **reconcile** — add newly
checked keys, delete unchecked-but-present ones. Continue is always enabled (it
may be a no-op advance). Leading button reads "Skip" instead of "Cancel".

Critical constraint: **the reconcile only ever touches catalog detectors, matched
by `identifier`, never custom ones.** If a key the library reported as present has
no matching detector row, it **throws rather than silently skipping** — a
deliberate fail-loud.

Also: search filters name + description; sections split RECOMMENDED / MORE
DETECTORS; entries with `accuracyCaveat` render a compact caveat notice inline;
and a standing footer note, "These start watching as soon as you add a camera",
which matches actual backend behavior.

---

## 23. Wandering exit-door picker ✅

Shows a **cached** camera frame with VLM-detected doorways as tappable dots.

Three tap behaviors, evaluated in order:
1. Within `hitRadius: 30pt` of a VLM dot → toggle selection (`source: "vlm"`)
2. Within 30pt of a manual dot → remove it
3. Empty space → place a manual door (`source: "manual"`), box `0.12 × 0.20`
   of the frame, clamped so it stays in bounds

Plus a "No door leads outside" action, which saves `noExitDoor: true` and turns
the detector off for that camera.

Load order matters: **frame first (fast, no LLM), then door detection in the
background.** The picker is usable immediately and dots appear when detection
returns — the caregiver can tap-place in the meantime.

Two IoU rules, both needed:
- Detected doors overlapping an already-placed manual door by **IoU > 0.4** are
  dropped, so two dots never stack.
- Restoring an existing config matches a stored box to a detected door at
  **IoU > 0.5**; anything unmatched is restored as a manual door.

Everything here is plain geometry (aspect-fit rect, normalized box centers, IoU)
and ports directly. The frame comes from `LiveKitFrameCapture` (see §12) and is
cached per camera, with a Rescan toolbar action to force a refresh.

---

## 24. Settings cards ✅

Three cards with near-identical layout (38pt icon tile, title, state-dependent
body copy, trailing toggle) but **three different persistence targets**. Do not
unify them into one component with a flag — the copy and the failure behavior
differ.

| Card | Persists to | Off-state copy |
|---|---|---|
| `PeopleBlurCard` | **device-local** (`PeopleBlurStore`) | "Applies to this device only." |
| `AudioCaptureCard` | **server** (`audio_capture_enabled`) | "No sound is captured, recorded, or analyzed. Required for care facilities." |
| `DataCollectionCard` | **server** (`data_collection_enabled`) | opt-in to the research corpus |

`AudioCaptureCard` is the facilities audio kill: off means publishers never
publish an audio track — severed at the source, so no live sound, no clip audio,
no speech alerts, and listening detectors fall back to visual-only. It reaches
gateway cameras within seconds via the roster long-poll. **On failure the card
renders the error text in place of the body copy, in terra** — it does not use a
separate banner. `enabled` reads `audioCaptureEnabled ?? true`.

### Data-collection consent sheet

Auto-presented once from `PatientShell` while `dataCollectionDecidedAt == nil`.
Two buttons: "Share recordings" → `true`, "Not now" → `false`. **Both record a
decision** (the backend stamps `decided_at` on either value), so it never re-nags.

On error the sheet **stays open and shows the message rather than recording a
phantom decision**. Analytics track labels are explicit:
`data_collection.consent_share` / `data_collection.consent_decline`.

Body copy interpolates the patient's preferred name and uses markdown bold; the
comment notes it must be a **single string literal**, not `+`-concatenated, or the
bold does not render.

---

## 25. The welcome tour ✅ (largest single subsystem after the API client)

1,026 lines. A click-by-click spotlight coach-mark system: the screen dims except
a pulsing cutout over the **real** control to tap next. **The user performs the
actual action; the tour advances off resulting app state — it never simulates
anything.**

### Structure

- **18 steps** (`welcome` → `done`), covering: add patient → fill form → choose
  detectors → face decision → face capture → open patient → sensors tab → add
  sensor → generate code → pair → live tab → live info → detectors → detectors
  info → events → events info.
- **12 targets** (`addPatient`, `nameField`, `submitPatient`, `enrollContinue`,
  `faceProceed`, `patientCard`, `addSensor`, `roomField`, `generateCode`,
  `sheetDone`, `firstDetector`, `reconnectAll`).
- **5 surfaces** ordered by z-depth (`root` < `patientShell` < `newPatientForm` <
  `addCameraSheet` < `enrollment`). Only the **topmost visible** surface renders
  the current step.
- Each step holds a **target priority list**; the host rings the first target that
  has reported a frame. That is how the ring walks name → DOB → submit
  automatically with zero extra logic.

### Four implementation decisions that must survive the port

**1. Conditional markers, not step logic.** A control reports its frame *only*
while it is the next thing to tap (name field reports while empty; submit reports
once valid). The ring walk is an emergent property of that.

**2. Global frames, not preference keys.** SwiftUI preferences do not reliably
escape `TabView` / `NavigationStack` (UIKit-backed). Each marker reports its
**global window frame** straight to the manager, and each host converts global →
its own space. Compose equivalent: `onGloballyPositioned` +
`boundsInWindow()`, with the same global-then-convert approach.

**3. The marker is identity-stable by design.** The background reader is **always**
installed and the optional target only gates *reporting*. An `if/else` would change
structural identity when the condition flips, SwiftUI would recreate the control,
and a focused text field would be torn down mid-word — that was the "ring jumps
after the first letter" bug. Compose has the same hazard with keyed recomposition.

**4. Surfaces are reference-counted, not a Set.** SwiftUI recreates an overlay host
when its wrapped view's navigation swaps, and **the new instance's `onAppear` fires
before the old instance's `onDisappear`**. A Set cannot survive that ordering — the
deferred disappear deleted a freshly-registered surface while the cover was plainly
on screen. Counting turns remount churn into a 1→2→1 no-op; a surface is gone only
at zero. Compose has the same disposal-ordering hazard.

### Interaction rules

- **STRICT MODE: no Next button.** Forward is tapping the spotlighted control or
  completing the prompted action. "Skip tour" is the only exit, so it can never
  trap anyone.
- `dimsAndBlocks(for:)` decides per step+target whether to gray out and gate taps.
  Free-choice screens (detector toggles, face consent) **ring without blocking**.
  `liveInfo` is two-phase: strict while "Reconnect all" is needed, then a plain
  explanation pause.
- **The ring holds while typing.** Tracked two ways because either alone misses:
  keyboard frame notifications (a no-op with a hardware keyboard or the
  simulator's Connect-Hardware-Keyboard) **and** text-field focus notifications.

### Persistence + eligibility

`UserDefaults` keys `tutorial.eligible`, `tutorial.done`, `tutorial.step`,
`tutorial.skippedPairing`. `markNewAccount()` is called **only from the sign-up
path** — never sign-in or restore — and deliberately **resets `done` to false and
step to 0**, because UserDefaults is per-device and a stale `done` from an earlier
account or test run would otherwise suppress the tour for a brand-new account.

Settings → "Replay the welcome tour" restarts on demand.

**Android assessment:** the state machine, step/target tables, priority lists, and
persistence port directly. The frame-reporting and surface-counting mechanisms have
Compose analogues with the *same* hazards, so port the reasoning, not just the
result. This is the largest Phase 8 item and should not be estimated as "an
overlay".

---

## 26. Enrollment camera + capture ✅

**Plain Vision, no ARKit / TrueDepth** — deliberately, so it works on the rear
camera and on older iPhones. Two requests per processed frame:
`VNDetectFaceRectanglesRequest` at **revision 3** (which is what supplies yaw and
pitch) and `VNDetectFaceCaptureQualityRequest`.

| Setting | Value |
|---|---|
| Session preset | `hd1280x720` |
| Processing rate | **15 Hz** (Vision every frame at 30fps is wasteful) |
| Pixel format | BGRA |
| Buffer rotation | 90° → upright portrait, so Vision and the JPEG both use `.up` |
| Face selected | **largest bounding box** when several are present |
| JPEG downscale | 640 wide, quality 0.8 |
| `maxUpload` | **40** (must match the backend cap; 2/arc + frontal ≈ 26) |

### Five behaviors that are easy to get wrong

**1. The preview is mirrored; the captured data is not.** Mirroring happens on the
*preview layer only*. The embedded JPEG is the true un-mirrored face, so the
gallery matches what the wall cam actually sees. Getting this backwards silently
poisons the re-ID gallery.

**2. Sign constants are device-tuned, per facing.**
`yawSign = front ? -1 : 1` (the front preview is mirrored, so its sign flips),
`pitchSign = -1` always. The comment says: if the dial fills opposite to the turn,
flip the sign here and nothing else changes. **Expect to re-verify both on a real
Android device** — this is a "verify once on hardware" constant, not a portable one.

**3. `wellFramed` has different geometry per camera, and it is about clipping, not
centering.** There is deliberately **no centering gate** — that made profiles slow
to land.
- Both: face height ≥ **0.16** of frame.
- **Front:** vertical band `[0.20, 0.80]`, horizontal `[0.04, 0.96]`. The tighter
  vertical bound exists because the round preview aspect-fills a tall sensor into a
  circle, cropping top and bottom — so the *visible ring* is only the middle
  ~[0.22, 0.78]. Gate on the sensor frame instead and a face spilling out of the
  ring (too close, chin cut off) wrongly reads as fine.
- **Rear:** full-bleed preview, so gate on the frame edge at 0.02.

**4. Work is skipped on frames that will not be kept.** The JPEG is encoded **only**
when `framed && quality >= floor`, so an unframed face can never green an arc. Scene
luma is computed **only** on low-quality frames, because its sole purpose is
explaining *why* a frame failed. Both matter at 15 Hz.

**5. Scene luma is a coarse subsample**, ~24×24 samples over the BGRA buffer with
Rec.601 weights (`0.114 B + 0.587 G + 0.299 R`), normalized to 0-1.

### Enrollment entry point

**Rear camera is the default** — the model is a nurse pointing at the resident and
walking around them. Front is self-enroll with the mirrored FaceID-style dial. Both
feed **one shared** `FaceCoverageModel` across camera switches, and the picker is
**locked once coverage is complete**.

Camera permission is re-checked on every foreground, so granting access in system
settings re-enables the camera without an app restart. "Done" is gated on
`coverage.isComplete`; "Skip" is always available.

### Android mapping

CameraX `ImageAnalysis` at 15 Hz replaces the AVFoundation path; ML Kit face
detection replaces `VNDetectFaceRectangles`. Coordinate conventions differ —
**Vision is normalized with a bottom-left origin; ML Kit returns pixel rects with a
top-left origin** — so `wellFramed`, the box normalization, and the tracking-ring
geometry all need converting, not copying. The `EnrollmentGeometry` aspect-fill
mapping (source aspect 720/1280) ports as plain math once the origin flip is
applied.

`faceCaptureQuality` remains the one true gap — see `QUESTIONS.md` Q2.

---

## 27. Enrollment UI shells ✅

Both dials share one lifecycle modifier and one guidance banner.

**Front (self-enroll):** a **mirrored circular preview** (300pt) inside the
coverage ring (ring is preview + 38pt). Headline: "Slowly turn your head in every
direction."

**Rear (nurse orbits):** full-bleed preview with the ring **anchored to the
tracked face** — diameter = `max(box.width, box.height) × 1.9`, positioned at the
box center, eased at 0.18s so it glides. Headline: "Slowly move around to every
side of them."

Both replace the preview with a **locked completion state** (spring-in green tick
+ "Enrollment complete") once `coverage.isComplete`, so the finished state reads as
locked rather than still-running.

### `CoverageDial` rendering rules

- 12 arcs with a **5° gap**, `lineWidth` 14 (front) / 12 (rear).
- Arc states: covered → green; current uncovered → faint base at 0.65 width **plus
  a terra charging fill** proportional to `needleFrac`; other → faint at 0.55 width.
- **Coordinate flip:** the model uses 0°=right, 90°=up counter-clockwise; the
  screen is y-down, so every angle is negated at draw time. Getting this wrong
  renders the dial vertically mirrored.
- **The hub is the frontal target**, not decoration. It greens once the frontal
  frame is captured, so "everything green" is genuinely the completion condition —
  no invisible gate.
- Needle color: grey when not well-framed, **green only when the tip is on an
  already-captured arc**, terra everywhere else. Green must not appear until the
  needle actually reaches a done edge.
- Needle: gradient shaft from the hub rim to the tip, plus a three-layer tip bead
  (halo, solid, white core).

### Haptics + lifecycle

`arcCaptured()` on each new arc or frontal capture, `completed()` on finish, with
the **arc buzz suppressed on the capture that also completes** so completion is a
single clean cue. Camera starts on appear and on permission grant, stops on
disappear and on completion. Haptics are kept out of `FaceCoverageModel` so the
model stays headless and testable.

Camera-denied state offers a **deep link into system settings**, paired with the
foreground re-check in `FaceEnrollmentView` so granting access and returning
re-enables the camera automatically.

---

## 28. Remaining surfaces ✅

**App settings:** caregiver card (→ edit profile), patient list, then Privacy &
data / Contact support / **Replay the welcome tour** / Sign out / Delete account.
Account deletion is a two-step alert; `isDeleting` clears in `defer` so a thrown
error can't permanently disable the button. Version label "MEMORIA v0.1".

**Edit profile:** name and phone go through `PATCH /v1/me`; **email goes through
Supabase's secure-email-change flow** and only becomes live after the user clicks
the emailed link, at which point a DB trigger mirrors it into `caregivers.email`.
Until then the displayed email stays the old one — **intentional, not a bug.**
Phone is required here too (≥10 digits). Save is gated on `hasChanges && isValid`.

**Forgot password:** succeeds whether or not the address is registered. Copy is
carefully non-committal: "If **{email}** is registered, a reset link is on its
way." Link expires in 1 hour.

**Reset password:** exchanges the deep-link code on appear; three states
(verifying / form / invalid). The invalid state names all three causes — missing,
expired, already used. On success `updatePassword` runs the same `onAuthenticated`
path as a normal sign-in, so the app lands on home directly.

**Welcome:** hero serif "The other 99.9% of care." with two CTAs.

**Feedback:** rating + "what's working" + "what's frustrating" (**the only required
field**) + up to **4 screenshot attachments**, downscaled to 1600px at quality 0.6
and base64-encoded. Success haptic on submit. Founders card includes a real
support phone number.

**Support button:** a perpetual floating "Text us" overlay applied to **both**
`MainTabView` and `PatientShell` — because a full-screen cover hides the
presenter's overlay. The expanded live view deliberately omits it. Opens an
iMessage-styled chat sheet posting to `/v1/support`.

**Privacy policy:** bundled static text, version-locked with the release so it
renders offline. Source of truth is the dashboard's `/privacy`; re-export when it
changes.

**Countries:** built from the platform's ISO region list so names localize for
free and 200+ labels are never hand-maintained, filtered to 2-letter alpha codes so
macro-regions drop out, sorted by localized name. Android: `Locale.getISOCountries()`
+ `getDisplayCountry()`, same filter and sort.

**Small components:** `NemaraPulse` (expanding ring, 1.6s ease-out, infinite),
`NemaraWaveform` (right-aligned bars, newest on the right, 40 bars, 6% floor so
silence still shows a baseline), `NemaraMark` (brand image clipped to a squircle at
radius = size × 0.2237, deliberately an image not rendered text so it stays in
pixel-parity with the app icon), `NemaraAvatar` (serif initial over a hue derived
from the letter: `(scalar × 47) % 360`, saturation 0.18, brightness 0.86).

---

## 29. Phase 0 complete

**All 103 files read at pin `a6c27f6`.** This document is the contract for phases
1-8. Open questions live in `QUESTIONS.md`; deliberate divergences in
`DIFFERENCES.md`.

### The ten things most likely to be ported wrong

1. **LiveKit identity suffix** — Android must be `_android`, or it kicks the
   caregiver's iPhone off the stream (§10).
2. **The three refetch paths** are not interchangeable (§10).
3. **The 1s poll must mirror the Realtime handler's side effects** exactly (§10).
4. **404/409 on three endpoints are control flow**, not errors (§10).
5. **`revokeAudio` must remove the identity from the enabled set**, not just zero
   volume — otherwise a resubscribe re-arms audio in a private room (§11).
6. **Audio is volume-gated, not subscription-gated**, on purpose (§11).
7. **Two SwiftUI workarounds must NOT be ported** — the deep-link two-tick dance
   and per-screen keyboard dismissal (§2).
8. **The detector-library onboarding reconcile fails loud** on a missing row (§22).
9. **The tutorial's surface registry must be reference-counted**, and its markers
   identity-stable (§25).
10. **Enrollment captures on the RAW pose**; only the needle is smoothed (§20).

### Scale estimate for phases 1-8

| Phase | Weight |
|---|---|
| 1 · scaffold + design system | 22 tokens, 3 font families, 14 components |
| 2 · networking + auth | 38 endpoints, 13 models, 10 lenient-decode rules, 6 screens |
| 3 · roster + events | 8 screens, 4 Realtime channels, the 1s poll |
| 4 · live video | **highest risk** — carries Q3 and the render watchdog |
| 5 · cameras + detectors + settings | largest screen count; carries Q5 |
| 6 · onboarding + enrollment | coverage model ports 1:1; carries Q2 |
| 7 · push + deep links | small; carries Q1 |
| 8 · remaining + tutorial | **the tutorial alone is a multi-day item** |
- `Analytics` (295 lines) + `AnalyticsView` screen tracking.
- DesignSystem: 22 color tokens, 3 font families, 11 shared components, spacing,
  radius.
- 57 feature files across Auth, Home, Patient (Live/Events/Cameras/Detectors/
  Settings), Onboarding + Enrollment, AppSettings, Legal, Feedback, Support,
  Tutorial.
