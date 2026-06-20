plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mahjongcoach.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mahjongcoach.app"
        minSdk = 26          // CameraX + modern SpeechRecognizer
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(project(":engine")) // the pure-Kotlin decision brain

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Coroutines + persisted settings (DataStore).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // AI assistant (optional layer). Official Anthropic Java SDK — Kotlin uses it
    // directly. The assistant always routes math through the engine tools.
    implementation("com.anthropic:anthropic-java:2.34.0")

    // OpenAI-compatible backend (any /chat/completions endpoint). Declared
    // explicitly so it doesn't ride on anthropic-java's transitive version.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX — for reading YOUR OWN hand (v2; see HandRecognizer).
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
