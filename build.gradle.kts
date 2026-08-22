// Nothing is applied or declared at the root, and there is deliberately no
// buildSrc convention plugin either.
//
// The constraint driving this layout: kotlin-android must be able to see the
// Android Gradle Plugin's classes. It can only do so if both are loaded by the
// same classloader. Anything that puts the Kotlin plugin on a classpath *above*
// :app - a root `plugins` block, even with `apply false`, or a buildSrc module
// that depends on kotlin-gradle-plugin - splits them across a parent and child
// loader, and the build dies with:
//
//     NoClassDefFoundError: com/android/build/gradle/api/BaseVariant
//
// The alternative fix, declaring AGP at the root too, would force AGP to
// resolve from Google's Maven on every build - including builds that only touch
// the pure-JVM modules, on machines that cannot reach that host.
//
// So each module declares its own plugins, in its own classpath scope, with
// versions pinned centrally in settings.gradle.kts. The small amount of
// repetition in the module build files is the price, and it is worth paying:
// it is what lets `./gradlew test` run with no Android SDK present at all.

subprojects {
    // Pure Gradle API only - no Kotlin or Android types - so this is safe to
    // apply from the root regardless of which plugins a module uses.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
