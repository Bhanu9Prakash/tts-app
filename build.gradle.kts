// No plugins are applied at the root, and none are declared here with
// `apply false`.
//
// Declaring the Kotlin plugin at the root would put it on the root build's
// classpath while the Android Gradle Plugin - applied only by :app - lives in a
// child classpath. kotlin-android needs to see AGP's classes, and across that
// classloader boundary it cannot: the build fails with a NoClassDefFoundError
// for com/android/build/gradle/api/BaseVariant.
//
// Declaring AGP at the root instead would fix that, but would force AGP to
// resolve from Google's Maven repository on every build - including builds that
// only touch the pure-JVM modules, on machines that cannot reach it.
//
// So: plugin versions are pinned centrally in settings.gradle.kts, the pure-JVM
// modules share configuration through the `voicecomposer.jvm-module` convention
// plugin in buildSrc, and :app applies AGP and kotlin-android together in its
// own classpath scope.
