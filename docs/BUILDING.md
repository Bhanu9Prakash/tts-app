# Building

## Requirements

| | Version |
|---|---|
| JDK | 17 or newer |
| Gradle | Provided by the wrapper (8.14.3) — do not install separately |
| Android SDK | API 35 platform, build-tools 34+ — **only needed for the APK** |

The Gradle wrapper is committed, so `./gradlew` bootstraps itself.

---

## Unit tests — no Android SDK needed

```bash
./gradlew test
```

133 tests across `core`, `commands`, `refinement-local`, `security` and
`model-manager`. Reports land in `<module>/build/reports/tests/test/index.html`.

This works on any JDK 17+ with no Android SDK installed and **no access to
Google's Maven repository** — every dependency of the pure-JVM modules comes
from Maven Central. That is a deliberate property, and CI checks it on every
push by blanking `ANDROID_HOME` for the test job.

When no SDK is detected the build prints:

```
[voice-composer] Android SDK not detected - configuring JVM modules only.
The :app module (and therefore the APK) is skipped.
```

That is expected, not an error.

---

## Building the APKs

Point the build at an SDK, either by exporting `ANDROID_HOME`:

```bash
export ANDROID_HOME=/path/to/android-sdk
```

or by writing `local.properties` in the repository root:

```properties
sdk.dir=/path/to/android-sdk
```

Then:

```bash
# Debug
./gradlew assembleSafeDebug assembleEnhancedDebug

# Release
./gradlew assembleSafeRelease assembleEnhancedRelease

# Everything
./gradlew assemble
```

Output:

```
app/build/outputs/apk/safe/debug/app-safe-debug.apk
app/build/outputs/apk/enhanced/debug/app-enhanced-debug.apk
app/build/outputs/apk/safe/release/app-safe-release.apk
app/build/outputs/apk/enhanced/release/app-enhanced-release.apk
```

The two flavours have different application IDs (`dev.voicecomposer.safe` and
`dev.voicecomposer`), so both install side by side.

---

## Signing a real release

Release builds are configured with the **debug** signing config so that
`assembleRelease` produces an installable artifact in CI. **That is not
suitable for distribution.**

For a real release, generate a key:

```bash
keytool -genkey -v -keystore release.keystore \
  -alias voicecomposer -keyalg RSA -keysize 4096 -validity 10000
```

Then replace the `signingConfig` in `app/build.gradle.kts` with one reading
credentials from environment variables or a `keystore.properties` file that is
**not committed**. No key, password or credential belongs in the repository or
in any Gradle file that is tracked.

---

## Verifying what you built

```bash
sha256sum app/build/outputs/apk/safe/release/app-safe-release.apk

aapt2 dump permissions app/build/outputs/apk/safe/release/app-safe-release.apk
```

The safe flavour should list exactly `RECORD_AUDIO`, `INTERNET` and
`ACCESS_NETWORK_STATE`. See `PERMISSIONS.md` for other tools and for the check
that confirms no accessibility service is present.

---

## Why the build is laid out this way

Two constraints shaped it, and both are worth knowing before editing the build
files.

### 1. `:app` is included conditionally

`settings.gradle.kts` includes `:app` only when an Android SDK is detected.
Without this, `./gradlew test` on a machine with no SDK fails at configuration
time — and worse, it would try to resolve the Android Gradle Plugin from
Google's Maven, which some environments cannot reach.

A blank `ANDROID_HOME` counts as absent, so CI can opt out explicitly.

### 2. Plugins are declared per module, never at the root

This looks like avoidable repetition. It is not.

`kotlin-android` needs to see AGP's classes, and can only do so if both are
loaded by the same classloader. Anything that puts the Kotlin plugin on a
classpath *above* `:app` — a root `plugins` block even with `apply false`, or a
`buildSrc` module depending on `kotlin-gradle-plugin` — splits them across a
parent and child loader, and the build fails with:

```
NoClassDefFoundError: com/android/build/gradle/api/BaseVariant
```

Both of those were tried during development and both failed exactly that way.

Declaring AGP at the root too would fix the classloader problem, but would force
AGP to resolve from Google's Maven on **every** build, including builds that
only touch the pure-JVM modules — which breaks constraint 1.

So: plugin versions are pinned centrally in `settings.gradle.kts`
(`pluginManagement.plugins`, which declares versions without resolving them),
each module applies what it needs in its own classpath scope, and the root build
script contains only pure-Gradle test configuration.

If you add a module, copy the `plugins` block from an existing one.

---

## CI

`.github/workflows/build.yml` runs two jobs:

**`jvm-tests`** — runs `./gradlew test` with `ANDROID_HOME` and
`ANDROID_SDK_ROOT` blanked, proving the security-critical logic builds and
passes with no Android SDK in play.

**`android`** — builds both flavours in both build types, then:

- prints every APK's SHA-256, size and full permission list to the job summary;
- **fails the build** if the safe flavour requests `SYSTEM_ALERT_WINDOW` or
  declares an accessibility service;
- publishes the APKs and a `SHA256SUMS.txt` as artifacts.

That second check is what keeps `PERMISSIONS.md` true over time: the flavour
split is verified against the packaged artifact, not asserted in prose.
