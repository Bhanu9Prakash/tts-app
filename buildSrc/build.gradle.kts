plugins { `kotlin-dsl` }

repositories { mavenCentral() }

dependencies {
    // Available on Maven Central, so this convention plugin - and therefore
    // every pure-JVM module - builds without Google's Maven repository.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
}
