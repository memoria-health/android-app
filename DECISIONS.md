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

_(Append as decisions are made. Never delete; mark REVERSED with the date.)_
