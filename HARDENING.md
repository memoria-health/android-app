# HARDENING.md — works, but must be made robust before ship

Things that are **implemented and passing** but that I would not want to meet a
real household without a second pass. Distinct from the other ledgers:

| File | Holds |
|---|---|
| `REQUIREMENTS.md` | blocked on something external — hardware, a credential, a decision |
| `QUESTIONS.md` | genuinely stops work until answered |
| `DIFFERENCES.md` | deliberate, justified divergence from iOS |
| **`HARDENING.md`** | **built and green, but thin, fragile, or unproven** |

Established 2026-08-02 at Aryaman's direction: keep building every phase, record
what will need fixing, harden at the end.

**Rule:** an entry here must name the failure it is guarding against, not just
say "improve X". If it cannot be stated as a failure, it is not hardening, it is
taste — and taste goes in the phase's review, not here.

---

## H-1 — Twelve of thirteen components have no test that renders them
**Phase 1 · found by review · effort: medium**

Only `NemaraButton` has composable-level tests. Everything else asserts
constants in isolation, so the values are pinned but never proven to be *wired
in*. `NemaraMark`'s clip could be changed to `Radius.lg` and the whole suite
stays green.

**Failure guarded:** a component whose constants are all correct and whose
rendering is wrong, shipped because the suite is green.

**Fix:** Robolectric + `createComposeRule` per component, asserting the rendered
tree. Cheap once the pattern exists — `NemaraButtonTest` is the template.

---

## H-2 — Waveform animation truncates on any recomposition
**Phase 1 · found by review · effort: small**

`NemaraWaveform`'s `LaunchedEffect` is keyed on a freshly-allocated `FloatArray`.
Compose compares keys with `==`, which for arrays is referential identity, so
**every** recomposition restarts the key — including ones where the levels did
not change. SwiftUI supersedes an animation only when the animated value
changes.

**Failure guarded:** an in-flight warm-up animation cut short mid-flight, which
reads as a stutter on the talk-mode waveform exactly when a caregiver is
speaking.

**Fix:** key on a stable value (the content hash or the sample list), not the
array instance.

---

## H-3 — Untested behavior the reviews flagged and I deferred
**Phase 1 · effort: small**

- `NemaraPulse`: `RepeatMode.Restart` is untested. `Reverse` would make the ring
  shrink back inward — a visible defect on every LIVE chip — and nothing catches it.
- `NemaraPulse`: the `Terra` / `8.dp` defaults are untested.
- `PatientAvatar`: the `|` separator in the refresh key is load-bearing per its
  own doc but untested; dropping it lets two patients collide.
- `PatientAvatar`: the clear-to-null on patient switch is untested, so removing
  it would silently regress to showing the previous patient's photo.

---

## H-4 — Several tautological tests
**Phase 1 and 2 · effort: small**

Tests that restate the implementation and cannot fail independently: they
inflate the count without adding coverage, which is worse than no test because
it reads as safety. Named by the reviewers in `NemaraPulse`, `NemaraMark`,
`PatientAvatar`, `CountryPicker` and the Phase 2 model files.

**Fix:** delete them, or replace each with the property it was gesturing at.

---

## H-5 — Model tests build their own Json instead of the shipping one
**Phase 2 · found by review · effort: small**

Several model test files construct a local `Json { ... }` rather than using
`NemaraJson.json`, so they never exercise the configuration the app actually
ships. A change to `ignoreUnknownKeys`, `explicitNulls` or `encodeDefaults`
would break the app and leave the suite green.

**Failure guarded:** a wire-format regression invisible to the tests that exist
specifically to catch wire-format regressions.

---

## H-6 — Four date accept-set edges differ from Foundation, untested
**Phase 2 · effort: small**

`JsonCoders` deliberately narrows a few inputs Foundation accepts — a 4-digit
year requirement is the notable one, so `999-01-01` and `20260-01-01` are
rejected where iOS takes them. No producer emits these and reading `26` as year
26 turns a typo into a plausible date, so the narrowing is defensible. It is
undocumented and unpinned, which is not.

Also: `0001-01-01T00:00:00Z` decodes two days off, because `java.time` applies
Gregorian rules before 1582 and Foundation switches to Julian. Unreachable for
real data.

---

## H-7 — `mapAuthError` matches on English substrings of SDK error text
**Phase 2 · RESOLVED ON ANDROID 2026-08-02 · still open on iOS**

Android now matches the typed `AuthErrorCode.InvalidCredentials`
(`DIFFERENCES.md` D12), with iOS's substring test kept only as a fallback for
deployments that answer without a code. **iOS still has the original fragility**
— left here so it is not lost.

iOS decides between `unauthorized` and a generic error by testing whether the
Supabase error description contains `"invalid login"` or `"invalid credentials"`.
That is fragile on iOS and worse on Android: the Kotlin SDK's error strings are
not guaranteed to match the Swift SDK's, and neither is stable across releases
or locales.

**Failure guarded:** a wrong password showing a generic "something went wrong"
instead of "Email or password is incorrect", because a vendor changed a string.

**Fix:** match on the SDK's typed error / HTTP status where one exists, and keep
the substring test only as a fallback.

---

## H-8 — Validation errors lose their field name when pydantic sends an index
**Phase 2 · inherited from iOS · effort: small**

`ValidationItem.loc` is typed `List<String>`, matching iOS's `[String]?`. But
pydantic's `loc` is heterogeneous — it mixes field-name strings with integer
array indices, e.g. `["body", 0, "name"]` for the second element of a list.
Decoding that as strings throws, the envelope is dropped, and the fallback copy
runs.

**Failure guarded:** a caregiver submitting a form with one bad field in a list
sees "Request failed validation (422)." instead of which field was wrong. Every
list-valued request body is exposed — detector rooms, camera detector ids, face
embeddings.

**Fix:** decode `loc` as `List<JsonPrimitive>` and stringify each element.
**Do it on both platforms together**, or the two disagree about what a caregiver
sees for the same server response.

---

## H-9 — The terms gate ships bricked: there are no PDFs
**Phase 2 · found by review · effort: medium · BLOCKS SIGN-UP**

`TermsGateScreen`'s `pageSource` defaults to `{ emptyList() }`, no PDF assets
exist anywhere in the Android project, and the failure branch emits no
end-of-document sentinel. So `canAccept` can never become true.

**Failure guarded:** a caregiver who signs up cannot get past the consent gate,
because the button that unblocks it only enables on scroll-to-end and there is
nothing to scroll. This is not cosmetic — it is a dead end in the sign-up flow.

**Fix:** vendor the placeholder PDFs iOS ships (version `2026-07-26-placeholder`,
REQ-10) into assets, and make the failure branch reachable so a load error
surfaces rather than silently disabling the button forever.

---

## H-10 — Screen analytics fire from inside screens, not from the nav host
**Phase 2 · effort: small**

Each auth screen calls `logScreen(...)` from its own `LaunchedEffect`, mirroring
iOS's `.trackScreen()` modifier. It works, but it means a screen added without
that line is silently untracked, and `forgot_password` is already missing.

**Failure guarded:** funnel gaps that look like user behaviour rather than
missing instrumentation.

**Fix:** fire `logScreen` once from the navigation host on destination change,
so tracking is structural.

---

## H-11 — Back chevrons drift 6dp between auth screens
**Phase 2 · found by review · effort: small**

The chevron on ForgotPassword sits 6dp right of the identical chevron on Login
and SignUp, so it visibly jumps when a caregiver goes Login → Forgot password →
back. Each screen draws its own because there is no shared top bar yet.

**Fix:** one shared auth top bar, which also gives H-10 its home.

---

_(Append as work lands. Each entry names the failure it guards against.)_
