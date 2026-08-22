plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.voicecomposer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.voicecomposer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // No API keys, endpoints or secrets are injected at build time. BYOK
        // credentials are entered by the user at runtime and stored via the
        // Keystore-backed CredentialStore. See docs/PRIVACY.md.
    }

    // -----------------------------------------------------------------------
    // Build flavours (product brief section 19).
    //
    // The "safe" flavour does not merely disable Flow Mode - the Accessibility
    // service, the overlay permission and the whole `flow` source set are
    // absent from the APK. That is a stronger guarantee than a runtime toggle,
    // because a user can verify it with `aapt dump permissions` rather than
    // having to trust the code.
    // -----------------------------------------------------------------------
    flavorDimensions += "integration"
    productFlavors {
        create("safe") {
            dimension = "integration"
            applicationIdSuffix = ".safe"
            versionNameSuffix = "-safe"
            resValue("string", "app_name", "Voice Composer")
            buildConfigField("boolean", "FLOW_MODE_AVAILABLE", "false")
        }
        create("enhanced") {
            dimension = "integration"
            versionNameSuffix = "-enhanced"
            resValue("string", "app_name", "Voice Composer+")
            buildConfigField("boolean", "FLOW_MODE_AVAILABLE", "true")
        }
    }

    sourceSets {
        // Accessibility/overlay code exists only in the enhanced flavour.
        getByName("enhanced") { java.srcDirs("src/enhanced/kotlin") }
        getByName("safe") { java.srcDirs("src/safe/kotlin") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debug signing is used only so `assembleRelease` produces an
            // installable artifact in CI. A real release must be signed with
            // the publisher's own key; see docs/BUILDING.md.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":speech-api"))
    implementation(project(":refinement-api"))
    implementation(project(":commands"))
    implementation(project(":refinement-local"))
    implementation(project(":security"))
    implementation(project(":model-manager"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // viewModelScope, used by ComposerViewModel.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Fully local speech recognition. Apache-2.0, prebuilt native libraries on
    // Maven Central, so no NDK build is required and no native code is ever
    // downloaded at runtime - only model data, which ModelInstaller enforces.
    implementation("com.alphacephei:vosk-android:0.3.75")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
