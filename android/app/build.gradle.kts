plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.npalakurla.jarvis.tablet"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.npalakurla.jarvis.tablet"
        minSdk = 30 // Lenovo TB-J606F runs Android 11 (API 30)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    // Issue #18: the debug build type had NO explicit signingConfig,
    // so AGP fell back to its default debug-keystore lookup — and the
    // CI restore step's keystore was evidently not picked up (run #9
    // signed a2b10c0f, run #10 signed f8fa22da, while the secret is
    // e71000e0). Pin the debug signing config explicitly to the
    // keystore file the CI workflow restores into the project dir
    // (android/debug.keystore, gitignored) when it exists. Every CI
    // debug APK is then signed with the one canonical secret key, no
    // HOME ambiguity. Local/VM builds without that file fall back to
    // AGP's default debug keystore (~/.android/debug.keystore).
    signingConfigs {
        getByName("debug") {
            val keystoreFile = rootProject.file("debug.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Release signing uses the Mini keystore (see README).
            // CI builds debug only; sign release on the Mini.
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
        buildConfig = true
    }
}

// Bundle the HUD: copy web/ into the APK assets before every build.
// web/ is the source of truth; android/app/src/main/assets/hud is
// generated (gitignored) and rebuilt by this task.
val copyHud by tasks.registering(Copy::class) {
    from(rootProject.file("../web"))
    into(layout.projectDirectory.dir("src/main/assets/hud"))
    // Never bundle a stray README as an asset page.
    exclude("README.md")
}

// Bundle the wake-word models: copy wake/*.onnx into the APK assets
// before every build. wake/ is the source of truth (the trained
// hey_jack.onnx + the shared openWakeWord melspectrogram/embedding
// models); android/app/src/main/assets/wake is generated (gitignored).
// When hey_jack.onnx does not exist yet, the copy is a no-op and
// WakeModel reports "missing" (normal until Phase 2 training lands).
val copyWakeModels by tasks.registering(Copy::class) {
    from(rootProject.file("../wake")) {
        include("*.onnx")
    }
    into(layout.projectDirectory.dir("src/main/assets/wake"))
}
tasks.named("preBuild").configure {
    dependsOn(copyHud)
    dependsOn(copyWakeModels)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // WakeModel imports ai.onnxruntime.* (Phase 2 model holder); the
    // dependency must stay declared or the app does not compile.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
