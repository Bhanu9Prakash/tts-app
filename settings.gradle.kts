pluginManagement {
    repositories {
        // Order matters: everything the pure-JVM modules need lives on the
        // first two repositories, so Google's Maven is only ever contacted for
        // Android-only plugins (the Android Gradle Plugin).
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    // Versions are declared, not applied. Declaring them here rather than in
    // the root build script means the Android plugins are only *resolved* when
    // the :app module is actually configured - so a machine without the Android
    // SDK (and without access to Google's Maven) can still build and test the
    // JVM modules.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
        id("com.android.application") version "8.5.2" apply false
        id("org.jetbrains.kotlin.android") version "2.0.21" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "voice-composer"

// ---------------------------------------------------------------------------
// Pure-JVM modules.
//
// Everything security-critical or logic-heavy lives here on purpose: these
// modules have no Android dependency, so they compile and their tests execute
// on any JDK without the Android SDK installed. See docs/ARCHITECTURE.md.
// ---------------------------------------------------------------------------
include(":core")
include(":speech-api")
include(":refinement-api")
include(":commands")
include(":refinement-local")
include(":security")
include(":model-manager")

// ---------------------------------------------------------------------------
// Android modules.
//
// The Android Gradle Plugin and the AndroidX/Compose artifacts are published
// only on Google's Maven repository, and the SDK itself only through the
// Android SDK manager. Where neither is reachable, we skip these modules
// rather than failing the whole build, so `gradle test` still runs every JVM
// test suite. Set ANDROID_HOME / ANDROID_SDK_ROOT, or write a local.properties
// containing sdk.dir=..., to include them. See docs/BUILDING.md.
// ---------------------------------------------------------------------------
// Blank is treated as absent, so a CI job can opt out with ANDROID_HOME: ""
// to prove the JVM modules still build with no Android SDK in play.
val androidSdkPresent: Boolean =
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkPresent) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle(
            "[voice-composer] Android SDK not detected - configuring JVM modules only. " +
                "The :app module (and therefore the APK) is skipped. See docs/BUILDING.md."
        )
    }
}
