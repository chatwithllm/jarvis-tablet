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

    buildTypes {
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
tasks.named("preBuild").configure {
    dependsOn(copyHud)
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
