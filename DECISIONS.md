# DECISIONS.md — the async review surface

Judgement calls made **without stopping Aryaman**, per `HARNESS.md §5`. Each entry
records the options considered and why one won, so any of them can be reviewed
later and reversed cheaply.

This exists because the standing instruction is to surface design choices before
implementing, and the instruction on this thread is to minimise stops. **The
compromise keeps the visibility and drops the blocking.** If an entry here looks
wrong, say so; reversing early is cheap.

Format: what was decided, the alternatives, the reason, and the blast radius if
reversed later.

---

## D-001 — minSdk 26, and blur by downscale rather than `RenderEffect`
**Date:** 2026-08-01 · **Reversal cost:** low before Phase 4, high after

**Options:** (a) minSdk 31 + `RenderEffect` only; (b) minSdk 26 + `RenderEffect`
on 31+ with a fallback; (c) minSdk 26 + one downscale-blur path everywhere.

**Chose (c).** `RenderEffect` is API 31+. API 31 sits near 85% of devices (API 34
was only ~54.5% in April 2026), so (a) drops roughly 15% — material for an India
pilot. (b) means two blur paths to tune and two to debug. For a sigma-36 blur at
video frame rates, downscale-blur-upscale is **both universally supported and
faster**, so (c) has no branch and drops no devices.

Full reasoning in `DIFFERENCES.md` D7.

---

## D-002 — The plan is per-phase, not one document
**Date:** 2026-08-01 · **Reversal cost:** low

`superpowers:writing-plans` wants complete code inline. At 22,224 lines that
document is the port. `PARITY.md` already holds that detail, so phase plans carry
task decomposition and commands and point at `PARITY.md §N` for behavior.
See `HARNESS.md §1`.

---

## D-003 — Branch per phase, no git worktrees
**Date:** 2026-08-01 · **Reversal cost:** low

`superpowers` prefers worktrees for isolation. This workspace's OneDrive/iCloud
sync aborts `checkout`/`merge`/`rebase` partway (workspace `CLAUDE.md`), and
worktrees multiply that surface. Single working copy, branch per phase.

---

## D-004 — Strict TDD on pure logic; compile + screenshot on UI
**Date:** 2026-08-01 · **Reversal cost:** low

Blanket TDD over Compose UI buys little and costs a lot. The four-tier strategy in
`HARNESS.md §4` puts strict red-green on Tier 1, which is where the entire risk
register lives. **Parity is judged by screenshot**, because that is the actual
product requirement for a port and no unit test expresses it.

---

## D-005 — Ktor over Retrofit
**Date:** 2026-08-01 · **Reversal cost:** medium (Phase 2 rewrite of one layer)

**Options:** (a) Retrofit + OkHttp + Moshi/kotlinx; (b) Ktor client + kotlinx.serialization.

**Chose (b).** `supabase-kt` already depends on Ktor, so (a) ships two HTTP stacks
and two JSON configurations in one app. The iOS client is one hand-rolled
`URLSession` layer with custom multipart and a bespoke date-decoding chain;
Ktor's explicit request building maps to that far more directly than Retrofit's
annotated-interface model, which fights both the multipart shapes
(`photo` single-part vs `photos` repeated-part) and the 404/409-as-control-flow
cases in `PARITY.md §10`.

---

## D-006 — applicationId `com.memoria.caregiver`, deliberately NOT mirroring iOS
**Date:** 2026-08-01 · **Decided by:** Aryaman · **Reversal cost:** low now, **infinite after first Play upload**

iOS is `co.sunny.nemara.caregiver` (`project.yml` `bundleIdPrefix: co.sunny`).
Android does **not** mirror it.

**Why:** an `applicationId` can never change once the app is uploaded to Play. With
"Sunny Transition" live on the company planning board, binding a new Play listing
to a personal namespace would be permanent. Android is a fresh listing, so this was
the free moment to move to a company namespace. iOS stays as-is; the two platforms
have no requirement to match.

**Watch for:** iOS persists under keys like `co.sunny.nemara.camerasEverOnline`
(`PARITY.md §10`). Those are per-platform local storage, so the prefix difference is
harmless — but do not "fix" the Android keys to match iOS, and do not assume a
shared namespace anywhere.

---

## D-007 — Git authority for the harness
**Date:** 2026-08-01 · **Decided by:** Aryaman · **Reversal cost:** none

Commit freely on phase branches, merge phase branches to local `main`. **No push,
no GitHub remote** without a fresh ask. Nothing leaves the machine. Review happens
asynchronously through this file and the phase summaries.

**Consequence to be aware of:** the work is unbacked-up and lives only in a
cloud-synced directory whose sync layer is already known to corrupt git operations
(workspace `CLAUDE.md`). Worth revisiting once Phase 1 lands.

---

## D-008 — Type tokens carry no letter spacing; kerning stays per call site
**Date:** 2026-08-01 · **Reversal cost:** high after Phase 3 (touches every screen)

**Found while porting, and it contradicted my own plan.** `PARITY.md §13` says
"kerning is applied per-site", which I had read as a detail. Reading
`NemaraFont.swift` directly: the tokens carry **family, size and weight only**.
Kerning and tracking live at **60 call sites** — 26 `.kerning()` with 9 distinct
values (-0.2 to -1.5) and 34 `.tracking()` with 7 distinct values (-0.1 to 8).

**Options:** (a) mirror iOS, tokens stay bare, kerning applied per call site;
(b) bake the documented values into the Compose `TextStyle`.

**Chose (a).** (b) is more idiomatic — Compose's `TextStyle` holds
`letterSpacing`, so it is the natural place. But it would apply kerning at every
call site **including the ~40 that deliberately have none**, shifting text
metrics across the whole app against the iOS screenshots. The divergence would
be invisible in code review and visible only in a side-by-side.

`TypeTest.kt` pins this with an assertion that all 20 styles have
`letterSpacing == TextUnit.Unspecified`, so a future "improvement" fails loudly.

**Consequence:** each screen ported in phases 2-8 must carry its own kerning from
the corresponding SwiftUI view. Flagged in the Phase 2 plan.

---

## D-009 — Robolectric 4.14.1, and its cold-start download hazard
**Date:** 2026-08-01 · **Reversal cost:** none

Robolectric **4.13 cannot run at all** against `targetSdkVersion 35`, failing every
test in the class with `targetSdkVersion=35 > maxSdkVersion=34`. 4.14.1 supports
API 35.

**Recorded because it will bite again:** the first full-suite run after the bump
failed one test (`ThemeTest @Config(sdk=[34])`) while the same test passed in
isolation. Cause was Robolectric fetching its `android-all` runtime jar per API
level on first use; once cached, two consecutive full runs were 60/60.

**Implication for CI:** a cold runner will hit this. Prefetch the `android-all`
jars or pin `robolectric.offline` with a local dependency directory before wiring
up CI, or the first build of every fresh runner fails for a reason that looks like
a real test failure.

---

## D-010 — Edge-to-edge is explicit, insets consumed at the root
**Date:** 2026-08-01 · **Reversal cost:** high after Phase 3 (every screen inherits it)

At `targetSdk 35`, Android 15+ **enforces** edge-to-edge and ignores
`android:statusBarColor`. The scaffold had that attribute set, which read as
working configuration but was a no-op on the newest devices, and nothing handled
insets — so the first real screen would have drawn under the status bar.

**Decided:** `enableEdgeToEdge()` in `MainActivity` with light scrims on both
bars (light-only app, so dark icons on Paper is the only correct combination),
and insets consumed once at the root via `safeDrawingPadding()`. That is the
Android equivalent of SwiftUI's implicit safe area, which every ported screen
assumes.

**Convention for phases 2-8:** screens inherit root inset padding. A screen that
deliberately bleeds under a bar — live video is the expected case — opts out
locally rather than the root opting out globally.

---

## D-011 — Gradle build directory lives outside the repo
**Date:** 2026-08-01 · **Reversal cost:** none

**This was not preventative; the build was already broken by it.** `./gradlew
test` failed with `Failed file name validation for .../fraunces_144_soft_medium
2.ttf` — a file the source tree never contained. iCloud had duplicated it inside
`packaged_res`, and `merged_res` held dozens of `" 2"` copies of `.flat` files.

Build output now goes to `~/.gradle-builds/memoria-caregiver-android`, overridable
with `-Pmemoria.buildDir=`. Redirecting is more reliable than asking every
developer to configure a sync exclusion, and it removes the churn the sync layer
reacts to.

The workspace `CLAUDE.md` documents cloud sync aborting git operations. Same
hazard, reaching the build.

---

## D-012 — Toolchain bumped to current stable on day one
**Date:** 2026-08-01 · **Reversal cost:** low (one commit, isolated)

The scaffold was pinned ~20 months stale: Compose BOM 2024.10.01 against a
current 2026.06.01, AGP 8.7.3 against 9.3.1, Kotlin 2.0.21 against 2.4.10.

**Options:** (a) bump now; (b) defer to Phase 2 or later.

**Chose (a)**, on a throwaway branch so abandoning was cheap. Doing it now cost
one commit with 83 tests as the net. Deferring would have meant a whole-toolchain
bump plus a Compose API sweep **in the middle of porting the API client**, since
Ktor and supabase-kt both track recent Kotlin.

Four things changed, none deep: AGP 9 has built-in Kotlin and rejects the
`kotlin-android` plugin; `variant.enableUnitTest` became the `hostTests` map;
Gradle 9 deprecated `Test.afterSuite`; `compileSdk` had to go to 36 because
`navigationevent 1.0.0` requires it. `targetSdk` stays 35 — compiling against a
newer SDK is independent of opting into its runtime behavior.

**The reflection-based palette test justified itself here.** The new Compose BOM
adds twelve "fixed" color roles that default to Material lavender; the test
failed and named all twelve. An enumerated test would have passed and shipped
them.

---

## D-013 — Release unit tests disabled; the gate is the debug suite
**Date:** 2026-08-01 · **Reversal cost:** none

`./gradlew test` — the gate `HARNESS.md §7` mandates — **had never passed.** Only
`testDebugUnitTest` was ever run, and that count was reported as if it were the
gate.

Compose UI tests need an activity to host the composition, supplied by
`ui-test-manifest`: a manifest-only artifact that is `debugImplementation` by
design, because shipping a test activity in a release build is not wanted. So the
release variant has no `ComponentActivity` and every `createComposeRule()` test
died with "Unable to resolve activity for Intent".

**Decided:** disable release unit tests. They add no coverage — same Kotlin, same
resources, minification off — and running the suite twice doubled gate wall time.
The alternative, putting `ui-test-manifest` on release, pollutes the shipped app
to satisfy a redundant suite.

---

## D-014 — The loudspeaker rule is enforced at mic publish, not in the talk layer
**Date:** 2026-08-02 · **Reversal cost:** small (move one call and one file)

Two implementations of the loudspeaker override existed (`FINDINGS.md` F-1).
They disagreed on the headset case, and **the one with the correct rule was the
one that never ran**: `TalkController`'s `audioRoute` parameter defaulted to
`TalkAudioRoute.None` and nothing in `main` ever passed anything else.

iOS puts the override in the talk path, so literal parity would keep it in
`TalkController`. **Rejected**, because the iOS structure carries an assumption
Android does not honour: on iOS there is exactly one way to publish the mic. Here
the collaborator is injected and defaultable, and it defaulted to doing nothing
for the entire life of the code. A privacy-adjacent invariant that a caller can
forget to pass is one that will be forgotten.

**Decided:** one implementation, in `LiveKitClient.setMicrophoneEnabled`, which
every mic publish goes through. The decision itself is extracted to
`data/livekit/AudioRouting.kt` as a pure function so it stays JVM-testable once
it sits behind a live `Room` — the SDK calls live in the client, the judgement
lives in the tested function.

The rule that survived is the headset-aware one: a wired or bluetooth headset is
left alone. Overriding it would put a conversation about someone's parent on a
loudspeaker in public, which is a worse failure than the earpiece bug the
override exists to fix.

**Cost, stated plainly:** `TalkController` can no longer observe the ordering of
mic-publish and route-change, and one test that asserted it was deleted rather
than rewritten. That ordering is now guaranteed by construction — the same
statement performs both — which is why the test had nothing left to assert.

---

## D-015 — Reset password is a cover, not a route
**Date:** 2026-08-03 · **Reversal cost:** small (one `if` and a view-model field)

`Routes.RESET_PASSWORD` and `Routes.resetPassword(code)` had existed unused since
the route table was written, so wiring `composable(Routes.RESET_PASSWORD)` was
the obvious way to reach `ResetPasswordScreen` when fixing `FINDINGS.md` F-26.
**Rejected**, for two independent reasons.

**The argument is a credential.** A route takes the recovery code as a path
argument. Navigation Compose keeps arguments on the back stack entry, and the
back stack is written into the Activity's saved-state Bundle — so a single-use
code would be persisted by the framework, survive process death, and remain on
the back stack after the screen that consumed it was gone. The code would also
appear in the route string in any navigation logging.

**It would be unreachable when signed in.** `AuthNavHost` renders the signed-out
graph only while `currentCaregiver` is null, so a route inside it cannot open
over the dashboard. iOS presents `ResetPasswordView` as a `fullScreenCover` on
RootView, which covers the welcome screen *and* the dashboard, and leaves what is
underneath alive.

**Decided:** the screen is presented from `AuthViewModel.reset`, a
`ResetPasswordSession?` drawn over both branches inside `AuthNavHost`. Null means
no link is open, so clearing the field and forgetting the code are one act. The
two route constants and `ARG_CODE` are deleted and replaced by a comment saying
why, so the next person does not re-add them.

**Cost, stated plainly:** the screen is now a conditional branch rather than a
route, so it needs its own `BackHandler` (F-25) and its own pointer-input guard
to stop touches reaching the screen underneath — both of which Navigation Compose
would have supplied. Those are one line each and are pinned by test; the
persistence of a credential would not have been.

---

_(Append as decisions are made. Never delete; mark REVERSED with the date.)_
