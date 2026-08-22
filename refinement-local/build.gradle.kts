import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

// Java 17 bytecode: the level AGP 8.x expects from a library it consumes. The
// target is set rather than a toolchain requested, so this builds on any
// JDK >= 17 without that exact JDK being installed.
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

dependencies {
    api(project(":core"))
    api(project(":commands"))
    api(project(":refinement-api"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}
