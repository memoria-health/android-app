# Phase 1 — Scaffold + Design System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to
> implement this plan task-by-task. The loop, the review rubric, the test tiers, and
> the escalation policy are in `HARNESS.md` — read it before Task 1.
> Behavior spec is `PARITY.md §13` (design system) and `§14` (analytics).

**Goal:** A building Android project whose design system reproduces the iOS one
token-for-token, verified by a component gallery screenshotted beside iOS.

**Architecture:** Single-module `:app` to start (splitting into `:core:designsystem`
etc. is a Phase 8 refactor if it earns itself — YAGNI now). Design system lives in
`ui/theme/` and `ui/components/`, mirroring the iOS `DesignSystem/` folder so a
reviewer can diff the two trees side by side. Theme is pinned light-only and
ignores the system dark setting, matching iOS.

**Tech Stack:** Kotlin 2.0.21, Compose BOM 2024.10.01, AGP 8.7.3, Gradle 8.9,
compileSdk/targetSdk 35, minSdk 26, JUnit4 + Robolectric + `compose-ui-test-junit4`.

**Why the design system comes before any screen:** every screen in phases 2-8
consumes these tokens. A wrong `Ink3` or a mis-rounded Fraunces weight found in
Phase 8 is 50 screens of rework.

---

## Environment (verified 2026-08-01)

```bash
export JAVA_HOME=~/Library/Java/temurin17
export ANDROID_HOME=~/Library/Android/sdk
```

Gradle 8.9 at `<scratchpad>/gradle-8.9/bin/gradle` for the first invocation; the
wrapper takes over after Task 1.

---

## Task 1: Gradle scaffold that builds

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
  `gradle/libs.versions.toml`, `gradle.properties`, `app/src/main/AndroidManifest.xml`,
  `app/src/main/java/com/memoria/caregiver/MainActivity.kt`,
  `app/src/main/res/values/themes.xml`, `gradlew` + wrapper

**Step 1: Write the version catalog.** All versions pinned in
`gradle/libs.versions.toml` — no version literals in any `build.gradle.kts`. This is
the one place Phase 2's Ktor/Supabase/LiveKit additions will land.

**Step 2: Minimal `MainActivity`** rendering `Text("Memoria")`. Nothing more; the
gallery replaces it in Task 14.

**Step 3: Verify the build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`

**Step 4: Commit** — `chore: gradle scaffold, version catalog, minimal activity`

> **Note:** the Phase 0 smoke build hit `resource style/Theme.AppCompat... not found`
> from referencing an AppCompat theme without the dependency. Use
> `android:Theme.Material.Light.NoActionBar` as the manifest theme; Compose supplies
> the real theming.

---

## Task 2: Test infrastructure, proven by a failing test

**Files:**
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `app/src/test/java/com/memoria/caregiver/HarnessSmokeTest.kt`

Adds JUnit4, Robolectric, `compose-ui-test-junit4`, `kotlinx-coroutines-test`,
Turbine. `testOptions.unitTests.isIncludeAndroidResources = true` for Robolectric.

**Step 1: Write a test that asserts something false**

```kotlin
@Test fun `harness proves red before green`() { assertEquals(1, 2) }
```

**Step 2: Run it — Expected: FAIL.** This is the point of the task. Per
`HARNESS.md §4`, an unobserved red phase means the test does not count, so the
harness itself must demonstrate it can go red before anything relies on it.

**Step 3: Flip to `assertEquals(2, 2)`, re-run — Expected: PASS.**

**Step 4: Commit** — `test: add JVM + Robolectric test infrastructure`

---

## Task 3: Color tokens (22)

**Files:**
- Create: `app/src/main/java/com/memoria/caregiver/ui/theme/Color.kt`
- Test: `app/src/test/java/com/memoria/caregiver/ui/theme/ColorTest.kt`

**Spec:** `PARITY.md §13`, the 22-row table. Values are extracted from the iOS
asset catalog.

**Step 1: Write the failing test.** Assert all 22 by exact ARGB, and assert the
five deliberate collisions:

```kotlin
@Test fun `paper is warm cream`() = assertEquals(0xFFEFE6DA, Paper.value shr 32)
@Test fun `action aliases ink but stays a distinct token`() = assertEquals(Ink, Action)
@Test fun `danger aliases terra`() = assertEquals(Terra, Danger)
@Test fun `info aliases sky`() = assertEquals(Sky, Info)
@Test fun `actionInk aliases card`() = assertEquals(Card, ActionInk)
@Test fun `actionSoft aliases paperDim`() = assertEquals(PaperDim, ActionSoft)
```

The collision tests exist to stop a future reader "cleaning up the duplicates."
`PARITY.md §13` is explicit: the values collide, the semantic names do not, and
call sites use the semantic name.

**Steps 2-4:** run red → implement → run green.
**Step 5: Commit** — `feat: 22 design-system color tokens`

---

## Task 4: Fonts in `res/font`

**Files:**
- Create: `app/src/main/res/font/*.ttf` (11 files)

**Step 1: Copy and rename.** Android resource names allow lowercase, digits and
underscore only:

| Source (iOS) | Android |
|---|---|
| `Fraunces144Soft-Regular.ttf` | `fraunces_144_soft_regular.ttf` |
| `Fraunces144Soft-Medium.ttf` | `fraunces_144_soft_medium.ttf` |
| `Fraunces144Soft-SemiBold.ttf` | `fraunces_144_soft_semibold.ttf` |
| `Inter_18pt-{Regular,Medium,SemiBold,Bold}.ttf` | `inter_18pt_{…}.ttf` |
| `JetBrainsMono-{Regular,Medium,SemiBold,Bold}.ttf` | `jetbrains_mono_{…}.ttf` |

Source: `nemara-caregiver-app/NemaraCaregiver/DesignSystem/Fonts/`. **Not** the
`build/` copies.

**Step 2:** `./gradlew assembleDebug` — Expected: `BUILD SUCCESSFUL` (proves the
resource names are legal).

**Step 3: Commit** — `chore: vendor Fraunces, Inter, JetBrains Mono`

---

## Task 5: Font families, including the Fraunces bold-rounding rule

**Files:**
- Create: `app/src/main/java/com/memoria/caregiver/ui/theme/Font.kt`
- Test: `.../ui/theme/FontTest.kt`

**Spec:** `PARITY.md §13`. **Fraunces ships no Bold cut**, so a `FontWeight.Bold`
request must resolve to the SemiBold file rather than letting the platform
synthesise a faux-bold. Inter and JetBrains Mono do have Bold.

**Step 1: Write the failing test**

```kotlin
@Test fun `fraunces has exactly three cuts and no bold`() {
    assertEquals(3, FrauncesFamily.fonts.size)
    assertTrue(FrauncesFamily.fonts.none { it.weight == FontWeight.Bold })
}
@Test fun `fraunces bold request resolves to semibold`() { /* resolve W700 → W600 */ }
@Test fun `inter and mono carry bold`() { … }
```

**Steps 2-4:** red → implement → green.
**Step 5: Commit** — `feat: font families with Fraunces bold-rounding`

---

## Task 6: The 20 named type styles

**Files:**
- Create: `.../ui/theme/Type.kt` · Test: `.../ui/theme/TypeTest.kt`

**Spec:** `PARITY.md §13` — 6 serif, 10 sans, 4 mono. Sizes, weights, **and the
per-site kerning** (`-0.8` on `title1`, `-0.3`/`-0.4` on serif rows, `0.4`-`0.7`
tracking on mono labels). The kerning is visible; a port that drops it looks
subtly wrong on every screen and is hard to diagnose later.

Test asserts family, size, weight and letterSpacing for all 20.

**Commit** — `feat: 20 named type styles with iOS kerning`

---

## Task 7: Spacing + radius scales

**Files:** `.../ui/theme/Dimens.kt` · Test: `.../ui/theme/DimensTest.kt`

Spacing `4, 8, 12, 16, 20, 24, 32, 40, 56` (xxs → huge).
Radius `sm 6`, `md 10`, `lg 14`, `xl 20`, `pill 999`.

**Commit** — `feat: spacing and radius scales`

---

## Task 8: Theme, pinned light-only

**Files:** `.../ui/theme/Theme.kt` · Test: `.../ui/theme/ThemeTest.kt` (Robolectric)

**Spec:** `PARITY.md §9` R8 — iOS is light-mode only. The Android theme must
produce identical colors under `uiMode = UI_MODE_NIGHT_YES`. No `isSystemInDarkTheme()`
call anywhere, and **no dynamic color** (Material You would repaint the brand from
the user's wallpaper).

**Step 1: Failing test** — render under night mode via Robolectric qualifiers,
assert background is still `Paper`.

**Commit** — `feat: light-only theme, no dynamic color`

---

## Task 9: Analytics seam

**Files:** `.../analytics/Analytics.kt` · Test: `.../analytics/AnalyticsTest.kt`

**Spec:** `PARITY.md §14`. Phase 1 needs only `logButton(name)` and
`logToggle(name, isOn)`, because Tasks 10 and 13 call them from inside the
components. The transport is Phase 8; this task defines the interface and a
recording fake so component tests can assert on it.

**Why now:** `PARITY.md §13` is explicit that instrumentation lives *inside*
`NemaraButton`/`NemaraToggle`, not at call sites — it is the single instrumentation
point for the whole app. Building the button without the seam guarantees someone
later sprinkles `logButton` at 200 call sites.

**Commit** — `feat: analytics seam with recording fake`

---

## Task 10: `NemaraButton` — 5 variants × 3 sizes, analytics inside

**Files:** `.../ui/components/NemaraButton.kt` · Test: `.../ui/components/NemaraButtonTest.kt`

**Spec:** `PARITY.md §13`.

- Variants: `primary` (Action bg / ActionInk fg), `terra`, `secondary` (Card bg,
  Line border), `ghost` (transparent), `danger` (Terra).
- Sizes drive font 13/14/16, vertical pad 8/12/16, horizontal pad 12/16/20.
- Radius `md` (10). Optional `block` (fill width), optional `systemImage` at
  fontSize + 2.
- **`Analytics.logButton(track ?: title)` fires before the action, synchronously.**

**Step 1: Failing tests** — the analytics one first, since it is the one most
likely to be dropped:

```kotlin
@Test fun `logs button title before invoking action`() {
    val order = mutableListOf<String>()
    // fake records "log", onClick records "action"
    assertEquals(listOf("log", "action"), order)
}
@Test fun `track label overrides title for analytics`() { … }
@Test fun `each variant maps to its background and foreground`() { … }
@Test fun `each size maps to its font size and padding`() { … }
```

**Commit** — `feat: NemaraButton with internal analytics`

---

## Task 11: `NemaraCard` — 6 tones

**Files:** `.../ui/components/NemaraCard.kt` + test

Tones `default / alt / terra / sky / success / action`, radius `lg` (14), 1px
border defaulting to `Line` and overridable.

**Commit** — `feat: NemaraCard`

---

## Task 12: Form components — `NemaraField`, `NemaraChip`, `NemaraToggle`

**Files:** `.../ui/components/{NemaraField,NemaraChip,NemaraToggle}.kt` + tests

`NemaraToggle` fires `Analytics.logToggle(track, isOn)` internally, same rule as
the button.

**Commit** — `feat: field, chip, toggle`

---

## Task 13: Display components

**Files:** `.../ui/components/{NemaraAvatar,PatientAvatar,NemaraPulse,NemaraWaveform,NemaraMark,NemaraEyebrow,NemaraCamFeedPlaceholder,NemaraSolidPill}.kt` + tests

`NemaraWaveform` is Phase 4's audio-level view. Phase 1 builds the **rendering**
against a supplied level list; the 80-sample ring buffer and the Int16 PCM
handling are Phase 4 (`PARITY.md §11`). Keep the seam: the component takes levels,
it does not source them.

**Commit** — `feat: display components`

---

## Task 14: `CountryPicker`

**Files:** `.../ui/components/CountryPicker.kt` + test

Search + dial-code list. Used by onboarding in Phase 6.

**Commit** — `feat: country picker`

---

## Task 15: Component gallery + screenshot parity

**Files:** `.../ui/gallery/ComponentGallery.kt`; `MainActivity` renders it in debug.

**Step 1:** Render every token and every component — all 22 colors as labelled
swatches, all 20 type styles as specimen rows, spacing/radius rulers, every button
variant × size, all 6 card tones, and each display component.

**Step 2: Build and install** — `./gradlew installDebug`, launch on `nemara_pixel`.

**Step 3: Screenshot beside iOS.** Per `HARNESS.md §4` this is a first-class gate,
not a nicety: a component with green tests and a wrong screenshot is not done.
Discrepancies go to `DIFFERENCES.md` with a reason or a fix.

**Step 4: Commit** — `feat: component gallery`

---

## Phase 1 exit criteria

- [ ] `./gradlew assembleDebug` exit 0, output pasted
- [ ] `./gradlew test` — all pass, count stated, 0 failures
- [ ] Gallery screenshot compared against iOS; deltas resolved or logged
- [ ] R8 (light-only) has a named test — the one risk-register item in this phase
- [ ] Dead-code sweep clean
- [ ] Adjudicated full-diff review across all 6 dimensions of `HARNESS.md §3`
- [ ] Phase 2 plan written

**Deliberately NOT in this phase (YAGNI):** module splitting, dark theme, dynamic
color, tablet/landscape (iOS is phone-portrait only), accessibility work beyond
what the components get free, animation polish.
