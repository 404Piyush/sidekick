plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sidekick.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sidekick.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "0.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("hackathonRelease") {
            // Reuse the auto-generated debug keystore so the release APK is
            // installable on any device without manual key management.
            // This is fine for the iQOO Hackathon demo (judges sideload via
            // `adb install`); it is NOT acceptable for Play Store distribution.
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hackathonRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.x compiler options DSL (replaces the deprecated
    // `kotlinOptions { jvmTarget = ... }` block). Same effect — pins
    // the JVM bytecode level for the compiled classes.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // Kotlin 2.x bundles the Compose Compiler — the `kotlin.plugin.compose`
    // plugin declared at the top of this file replaces the legacy
    // `composeOptions { kotlinCompilerExtensionVersion = ... }` block.
    // Pinning a compose-compiler version here is no longer supported
    // and would crash the build.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM: pinned to 2024.12.01 (Compose 1.7.6). Kotlin 2.2.x's
    // bundled Compose Compiler generates code compatible with Compose
    // runtime 1.6+, so we do NOT need to jump to the 2026.x BOM line
    // (which pulls Compose 1.11 + lifecycle 2.9.4, both of which demand
    // compileSdk 35 + AGP 8.6+). 2024.12.01 stays on compileSdk 34 + AGP
    // 8.5.2 while still compiling cleanly under Kotlin 2.2.21.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- M1: provider layer ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- M7: on-device LLM via LiteRT-LM ---
    // Google AI Edge's on-device inference runtime. The dependency pulls
    // in ~18 MB of native libraries (.so) for CPU/GPU/NPU backends — we
    // ship them all so the user can pick at runtime. The SDK isn't on
    // Maven Central; it lives on Google Maven only. Bumped the Kotlin
    // compiler to 2.1.21 because LiteRT-LM is shipped with Kotlin 2.x
    // metadata and won't load under 1.9.x.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // --- M2: Room persistence ---
    // Bumped from 2.6.1 to 2.8.4 in M7 because the older Room compiler
    // doesn't understand Kotlin 2.x metadata (KSP fails with "unexpected
    // jvm signature V" on the @Entity classes). Room 2.8 ships explicit
    // KSP2 support and tracks the latest Kotlin compiler.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")

    // Instrumented Compose UI tests (MarkdownRendererTest lives under
    // androidTest/ — it uses createComposeRule, which needs a device).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Robolectric so in-memory Room can run on the JVM in unit tests
    testImplementation("org.robolectric:robolectric:4.12.2")
}