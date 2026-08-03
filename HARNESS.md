# HARNESS.md — how this port runs

> The development loop for the Android port. Read once. It governs every phase so
> that phases do not each get re-negotiated.
>
> Built on the `superpowers` plugin skills: `writing-plans`, `subagent-driven-development`,
> `requesting-code-review`, `test-driven-development`, `verification-before-completion`,
> `finishing-a-development-branch`.

**Established 2026-08-01 at Aryaman's request:** "create a harness out of this
development process so we can get started with minimal stops."

---

## 0. The four documents and what each is for

| Doc | Role | Who writes it |
|---|---|---|
| `PARITY.md` | **The spec.** 29 sections, 103 files, frozen at pin `a6c27f6`. The thing reviewers review *against*. | Phase 0, done |
| `HARNESS.md` | This file. The loop. | Once |
| `docs/plans/YYYY-MM-DD-phase-N-*.md` | The per-phase task list, superpowers format, bite-sized TDD tasks. | Written at each phase start |
| `DECISIONS.md` | Every judgement call made without stopping Aryaman, with the options considered. **The async review surface.** | Continuously |
| `QUESTIONS.md` | Things that genuinely stop work. | Continuously |
| `DIFFERENCES.md` | Deliberate divergences from iOS. | Continuously |

`PARITY.md` is the spec because the Kotlin cannot be reviewed directly at 22k
lines. **Every review is a diff against a PARITY section, never a freeform
opinion.**

---

## 1. Why the plan is per-phase, not one document

The `writing-plans` skill wants complete code inline and 2-5 minute tasks. For a
22,224-line port that document *is* the port — writing it costs the same as doing
it, and it would be stale before Phase 3.

**Adaptation:** `PARITY.md` already carries the "complete detail" that
`writing-plans` normally puts in the plan. Each phase plan therefore carries the
*task decomposition, test list, and exact commands*, and points at
`PARITY.md §N` for behavior. One plan per phase, written at phase start, when the
previous phase's real shape is known.

This is the only deviation from the skill, and it is recorded here rather than
made silently.

---

## 2. The per-task loop

Per `superpowers:subagent-driven-development`, with one addition (Aryaman's
review dimensions merged into stage 2).

```
for each task in the phase plan:
  1. implementer subagent   → TDD: red, green, refactor, commit, self-review
  2. spec reviewer subagent → does it match PARITY.md §N? nothing missing, nothing EXTRA
     └ issues? implementer fixes → re-review. Loop until ✅.
  3. quality reviewer subagent → the 6-dimension rubric below
     └ issues? implementer fixes → re-review. Loop until ✅.
  4. mark complete
```

**Never** start stage 3 before stage 2 is green. **Never** advance with an open
issue at either stage. **Never** run two implementer subagents in parallel.

At phase end: one adjudicated full-diff review over the whole phase, then
`superpowers:finishing-a-development-branch`.

---

## 3. The review rubric — 6 dimensions

Merged from Aryaman's standing mandate (correctness / efficiency / memory /
security / goal-adherence), his ask on this thread (logic / security /
scalability / cleanliness), and the superpowers two-stage split. **One rubric, so
there is one thing to satisfy rather than three.**

| # | Dimension | The question | Auto-fail examples |
|---|---|---|---|
| 1 | **Spec compliance** | Does it match `PARITY.md §N`? | Missing behavior. **Anything extra that iOS does not do.** |
| 2 | **Logic** | Is it correct at the boundaries? | Off-by-one, wrong null handling, race on shared state, wrong coroutine scope |
| 3 | **Security & privacy** | Would this leak? | Token/PII in logs, cleartext outside debug, secret in VCS, RLS assumption unverified, missing `setAuth` pump |
| 4 | **Performance & memory** | Does it survive a 24h session? | Leaked listener/collector, unbounded buffer, allocation in a frame callback, recomposition storm, bitmap not recycled |
| 5 | **Cleanliness** | Is anything dead? | Unreachable branch, unused symbol, commented-out code, stale TODO, copy-paste duplication. **Delete it — do not comment it out.** |
| 6 | **Test quality** | Do the tests test behavior? | Test asserts the implementation back at itself, no red phase observed, mock-only test with no real path |

Severity: **Critical** (fix now, blocks the task) / **Important** (fix before the
phase ends) / **Minor** (log it, batch it).

---

## 4. Test strategy — four tiers, honest about what TDD means here

Blanket "TDD everything" does not survive contact with Android UI. What follows
is where tests actually pay, and it is not a compromise: **the risk register is
almost entirely pure logic, so the tier that gets strict TDD is the tier that
carries the risk.**

| Tier | Where | What | Discipline |
|---|---|---|---|
| **0 · Compile** | `./gradlew assembleDebug` | everything | **Green at every commit. No exceptions.** |
| **1 · JVM unit** (`src/test/`) | pure Kotlin, no Android | decoders, date fallback chain, error mapping, camera-state diff, `FaceCoverageModel`, deep-link reconcile, blur box math, connectivity fusion, analytics naming | **Strict TDD. Red observed before green.** |
| **2 · Robolectric + Compose UI** (`src/test/`) | JVM w/ Android shims | component state, form validation, navigation, gating (terms, privacy placard) | **Test-first for stateful behavior**, test-after acceptable for pure rendering |
| **3 · Instrumented** (`src/androidTest/`) | real device | LiveKit, CameraX, ML Kit, FCM | **Cannot TDD.** Written now, **run day 9-10.** Accepted risk, already logged. |

**Plus, and it is a first-class gate: the screenshot parity check.** Parity is the
product requirement, so for any screen the test of record is a screenshot beside
the iOS original. A screen with green unit tests and a wrong screenshot is not
done.

### The two rules that matter most

- **Red before green.** If the failing test was not observed failing, it does not
  count and the task is not done. From `verification-before-completion`: *if you
  haven't run the command in this message, you cannot claim it passes.*
- **Every risk-register item gets a named test.** `PARITY.md §9` R1-R8 and the
  §29 "ten things most likely to be ported wrong" each map to at least one Tier-1
  test. That list is the regression suite for the port.

---

## 4b. End-to-end testing: the emulator

**The Android Emulator is the iOS Simulator's counterpart, and it is a stronger
tool.** The difference is architectural and it works in our favour:

| | iOS Simulator | Android Emulator |
|---|---|---|
| What it is | your app built for the **host** arch, against a shim | a **real Android OS** in a QEMU VM |
| Framework | simulator-specific reimplementation | the actual framework, Binder, HALs |
| Camera | limited | virtual scene, host webcam, **image or video file** |
| Consequence | many things "need a real device" | most things do not |

On Apple Silicon the arm64 system image runs natively. Our AVD `nemara_pixel`
cold-boots in ~25s, and the app installs and launches.

### What this changes

`nemara_pixel` was originally configured with **`hw.camera.front = none`** and
GPU disabled — so face enrollment, which self-enrolls on the *front* camera,
could not have been exercised at all. Now configured: both cameras `emulated`,
`hw.gpu.mode = host`, 4 GB RAM, audio in and out.

Crucially, `-camera-front videofile:<path>` renders a **video file as the camera
feed**. A single recorded clip of a face turning through the yaw range drives the
entire enrollment coverage model deterministically, in CI, with no human and no
device. That is a repeatable test of the thing I had written off as
device-only.

### Capability matrix for the risky phases

| Capability | Emulator | Notes |
|---|---|---|
| Backend over HTTP | **yes** | host is `10.0.2.2` from the guest — verified by ping |
| Supabase Realtime, WebSockets | **yes** | ordinary networking |
| LiveKit video playback | **yes** | software decode; correctness yes, performance no |
| Talk mode mic capture | **yes** | `hw.audioInput = yes`, host mic passthrough |
| Face enrollment | **yes** | `-camera-front videofile:` with a scripted clip |
| FCM push | **yes** | Google APIs image carries Play services |
| Deep links | **yes** | `adb shell am start -a android.intent.action.VIEW -d "nemara://…"` |
| Screenshot parity | **yes** | `adb exec-out screencap -p` |
| Rotation, locale, font scale, dark mode | **yes** | all injectable via `adb` |
| **Blur at video frame rates** | **no** | emulated GPU gives no usable perf signal |
| **Real Tapo camera / gateway on LAN** | **no** | needs the real appliance |
| **Sensor Wi-Fi AP join (Q5)** | **no** | no real Wi-Fi stack |
| **Enrollment quality calibration** | **no** | needs real faces in real light |

### The ladder

```
Tier 0  compile          ./gradlew assembleDebug
Tier 1  JVM unit         ./gradlew test                    ← the risk register
Tier 2  Robolectric      (same command)                    ← component behavior
Tier 3  instrumented     ./gradlew connectedDebugAndroidTest   ← emulator, real framework
Tier 4  scripted E2E     adb-driven flow + screenshots     ← full journeys
Tier 5  physical device  the four rows marked "no" above
```

Tiers 3 and 4 run on the emulator and belong in CI. **Tier 5 shrinks to four
things**, which is the point: it is no longer "phases 4 and 6 are unverifiable
until day 9."

### Standing commands

```bash
export ANDROID_HOME=~/Library/Android/sdk
$ANDROID_HOME/emulator/emulator -avd nemara_pixel -no-snapshot-load -no-boot-anim &
adb wait-for-device
./gradlew installDebug
adb exec-out screencap -p > shot.png

# enrollment, deterministic:
emulator -avd nemara_pixel -camera-front videofile:$PWD/testdata/face-yaw-sweep.mp4
```

---

## 4c. The coverage gate: every iOS file must be accounted for

Added 2026-08-02, after `FINDINGS.md` F-14 found four iOS screens with **no
Android counterpart at all** — `PHeader`, `PatientDetectorsView`,
`EventReviewView`, `SupportContactView` — one of which was only noticed because
a shell could not be built without it.

**Why nothing caught them.** Every review checked that what was built matched the
Swift. None checked the inverse. Each phase's scope came from a list I wrote, so
a file I left off that list was invisible to every gate afterwards — including
the test suite, which can only test code that exists. A missing file produces no
failing test, no compile error and no review finding. It produces nothing at
all, which is exactly why it survives.

**The gate.** Before a phase can be called done, run the sweep and account for
every unmatched iOS file:

```bash
cd nemara-caregiver-app && find NemaraCaregiver -name "*.swift" \
  | xargs -n1 basename | sed 's/\.swift$//' | sort -u
```

**`find NemaraCaregiver`, not `Features` and `App`.** The first version of this
gate globbed only those two directories — 61 of 103 files — and never swept
`Services/`, `Models/`, `Networking/` or `DesignSystem/`. That is exactly where
`Analytics.swift` (295 lines, entirely unported, FINDINGS.md F-28),
`AnalyticsView.swift`, `WanderingDoorCache.swift` and `LegalConstants.swift`
live. **The gate written to catch missing files had the same blind spot that
produced them**, and a later review found them by reading rather than by the
gate. Sweep everything.

against the Android `ui/` tree. Name matching is a starting point, not the
answer — `RootView` maps to `AuthNavHost`, `MainTabView` to `MainShell` — so
each unmatched name resolves to exactly one of:

1. **ported under a different name** — say which file, and move on;
2. **deliberately not ported** — it must already be in `DIFFERENCES.md` or
   `QUESTIONS.md`; if it is not, it is not deliberate, it is missing;
3. **missing** — a row in `FINDINGS.md`.

`PARITY.md` §29 enumerates *behaviours* that must survive the port. Nothing
enumerated *files*, and that was the hole.

---

## 4d. Mutation testing must confirm the test RAN, not just that nothing failed

Added 2026-08-02, after my own mutation harness reported four consecutive
"SURVIVED" results that were all false.

The script applied a mutation, ran `./gradlew testDebugUnitTest --tests
'*XTest*'`, grepped for a line ending `FAILED`, and reported "survived" when it
found none. A concurrent agent had left a **test source file that did not
compile**, so `compileDebugUnitTestKotlin` failed and *no test ran at all*. No
test ran, therefore no test failed, therefore every mutation "survived".

**Absence of a failure is not evidence of a pass.** This is the same shape as
the empty-source-set guard that did not guard (DECISIONS.md D-013's neighbour):
a gate that reports success when its subject never executed.

**The rule:** a mutation run must assert a POSITIVE signal — the intended test
executed and failed. In practice:

```bash
out=$(./gradlew testDebugUnitTest --tests '*XTest*' --console=plain 2>&1)
echo "$out" | grep -q "TEST SUMMARY" || { echo "HARNESS BROKEN: nothing ran"; exit 1; }
echo "$out" | grep -q "the intended test name.*FAILED" || echo "SURVIVED"
```

The `TEST SUMMARY` check is the load-bearing line: this project's
`verifyUnitTestsRan` finalizer prints it, so its absence means the suite did not
execute and **the result is void, not green and not red**.

Corollary for parallel work: one agent's broken test file breaks the *module's*
test compile, so nobody else can run anything. When several agents are active,
a red or empty run is as likely to be somebody else's half-written file as it is
to be yours — check `compileDebugUnitTestKotlin` before believing any verdict.

---

## 5. Escalation — exactly what stops the machine

Aryaman's standing instruction is to surface design choices before implementing.
On this thread he asked for minimal stops. **The reconciliation: keep the
visibility, drop the blocking.** Judgement calls get made and written to
`DECISIONS.md` with the options and the reasoning, so they can be reviewed and
reversed asynchronously instead of serially.

### STOP and wait (these are the only ones)

1. An open `QUESTIONS.md` item lands on the critical path.
2. A divergence is needed that is **not** platform-mechanism — i.e. a redesign.
   (`DIFFERENCES.md` states the bar: "cleaner" and "more idiomatic" are not reasons.)
3. A **backend or contract change** is required. Never change the backend to suit
   the client mid-port.
4. Something needs a **credential, a paid account, a device, or an app-store
   identity**.
5. A task turns out to be **>2× its estimate**, so scope is Aryaman's call.
6. A **user-visible product decision** with no iOS precedent (Q5 is the live example).

### Do NOT stop (decide, log to `DECISIONS.md`, continue)

Library choice, file layout, naming, test structure, Compose idiom, error copy
that already exists in `PARITY.md`, anything the iOS source already answers.

**When stopped:** work the rest of the phase around it. Halt the item, not the
phase.

---

## 6. Git

The repo is fresh, has no deployed code, and lives under a cloud-synced tree.

- **No worktrees.** `superpowers` prefers them, but OneDrive/iCloud stat races
  abort `checkout`/`merge`/`rebase` in this workspace (see workspace `CLAUDE.md`).
  Worktrees would multiply that failure. **Branch per phase in one working copy.**
- Branch naming: `phase-N-<slug>` (e.g. `phase-1-design-system`).
- Commit per green test cycle. Conventional commits (`feat:`, `fix:`, `test:`,
  `chore:`).
- Phase end → full-diff review → merge to `main`. `main` stays green.
- **Nothing is deployed from this repo yet**, so `main` merges do not carry the
  production risk that `nemara-backend` and `nemara-caregiver-app` do. When the
  first release build ships, this rule tightens to match the other repos.

---

## 7. Phase-end checklist

Every phase, no exceptions. **Run the exact commands below** — the first time
this checklist was used, the mandated command had never actually been run and a
narrower one was reported in its place.

```bash
export JAVA_HOME=~/Library/Java/temurin17/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
./gradlew test assembleDebug
```

- [ ] `./gradlew assembleDebug` — exit 0, output pasted
- [ ] `./gradlew test` — pass count stated, 0 failures. Release unit tests are
      disabled (`DECISIONS.md` D-013), so this is the debug suite; the build fails
      if zero tests ran, so a printed count is real.
- [ ] Every risk-register item touched this phase has a named test
- [ ] Screenshot parity pass for every screen added
- [ ] `DECISIONS.md`, `DIFFERENCES.md`, `QUESTIONS.md` current
- [ ] Adjudicated full-diff review across all 6 dimensions
- [ ] Dead code sweep: zero unused symbols, zero commented-out blocks
- [ ] Plan doc checkboxes ticked; next phase's plan written

---

## 8. What Aryaman reads

Deliberately small, because attention is the scarce resource:

1. **`DECISIONS.md`** — async, whenever. The reversal surface.
2. **`QUESTIONS.md` STOP items** — these are actually blocking.
3. **Phase-end summaries** — one screen each, with the verification output.
4. **Screenshot diffs** — the only reliable way to judge parity, and the one
   thing a subagent genuinely cannot self-verify.

Not for review: the Kotlin, task-level commits, subagent review transcripts.
`PARITY.md` is the gate that makes skipping those safe.
