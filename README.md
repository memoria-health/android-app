# nemara-caregiver-app-android

Native Android port of the Memoria caregiver app (`nemara-caregiver-app`).
Kotlin + Jetpack Compose. Not a cross-platform rewrite.

## The four documents

Read these before writing code. They are the contract, not background reading.

| File | What it is |
|---|---|
| [PORT.md](PORT.md) | The porting brief: rules, stack decisions, phases, escalation policy |
| [PARITY.md](PARITY.md) | The feature-parity spec derived from the iOS source. **The review gate** |
| [QUESTIONS.md](QUESTIONS.md) | Blocking questions. Policy is stop-and-wait, never best-guess |
| [DIFFERENCES.md](DIFFERENCES.md) | Every deliberate divergence from iOS, with its reason |

Progress tracker: [docs/plans/2026-08-01-android-port.md](docs/plans/2026-08-01-android-port.md)

## Source of truth

Port source is `nemara-caregiver-app` at commit
`a6c27f64afd93fb2c44d17c6c7eccd02928cae7c` (branch `feat/gateway-camera-ownership`).
`main` is 18 commits stale and does not represent shipping behavior.

The iOS repo is **read-only** from here. Never modify it.

## Toolchain

Verified 2026-08-01 with a real Compose APK build.

| Component | Version |
|---|---|
| JDK | Temurin 17.0.20 (arm64) |
| Gradle | 8.9 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

The JDK and Android SDK are installed outside Homebrew — the local brew is the
Intel prefix on an arm64 Mac and would pull x86_64 builds.

```bash
export JAVA_HOME="$HOME/Library/Java/temurin17/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## Per-developer config

Unlike iOS — which commits `LocalAppConfig.swift` and asks each developer to
`git update-index --skip-worktree` it — the Android equivalent is **gitignored
from creation**, with a committed `.template`. A developer's LAN address must not
be able to reach a commit. See `DIFFERENCES.md` D3.

## App shape

Phone only. Portrait only. Light mode only. These match iOS
(`TARGETED_DEVICE_FAMILY: 1`, `UIRequiresFullScreen`, `preferredColorScheme(.light)`)
and are not preferences to revisit.
