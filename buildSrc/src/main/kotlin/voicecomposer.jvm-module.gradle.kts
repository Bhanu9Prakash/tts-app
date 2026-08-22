import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention plugin for the pure-JVM modules.
 *
 * These modules deliberately have no Android dependency, so they compile and
 * their tests run on any JDK 17+ with no Android SDK installed. Keeping the
 * shared configuration here rather than in the root build script means the root
 * script needs no Kotlin plugin on its classpath - which in turn keeps the
 * Android Gradle Plugin out of the root classpath, so :app can apply AGP and
 * kotlin-android together in one classloader. See the comment in the root
 * build.gradle.kts for why that matters.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

dependencies {
    add("testImplementation", "org.jetbrains.kotlin:kotlin-test:2.0.21")
}

// Java 17 bytecode: the level AGP 8.x expects from a library it consumes. We
// target the level rather than requesting a toolchain, so the build works on
// any JDK >= 17 without that exact JDK being installed.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
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
