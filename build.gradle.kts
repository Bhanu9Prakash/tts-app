import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.0.21" apply false
}

// Configuration shared by every pure-JVM module. The Android module applies its
// own plugins and is configured in app/build.gradle.kts.
subprojects {
    if (name == "app") return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    // java-library so modules can expose shared domain types via `api`.
    apply(plugin = "java-library")

    // Repositories are declared centrally in settings.gradle.kts.

    dependencies {
        add("testImplementation", kotlin("test"))
    }

    // These modules are consumed by the Android app, so they emit Java 17
    // bytecode - the level AGP 8.x expects. We deliberately target the level
    // rather than requesting a toolchain, so the build works on any JDK >= 17
    // without needing that exact JDK installed.
    extensions.configure<KotlinJvmProjectExtension>("kotlin") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    extensions.configure<JavaPluginExtension>("java") {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
