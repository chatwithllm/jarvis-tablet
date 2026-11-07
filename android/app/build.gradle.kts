import java.net.URI
import java.security.MessageDigest

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
        versionCode = 5
        versionName = "0.4.3-vosk-stt"
        // TB-J606F is arm64. Dropping the other ABIs keeps Vosk (and any
        // leftover native deps) from shipping three extra copies of libvosk.
        ndk {
            abiFilters += "arm64-v8a"
        }
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

// Grammar + Vosk model are generated under build/, then added as an
// asset root. wake/phrases.json stays the source of truth for the
// desktop eval script. The model is not committed.
val generatedWakeAssets = layout.buildDirectory.dir("generated/vosk-assets")
android.sourceSets.getByName("main").assets.srcDir(generatedWakeAssets)

val copyWakePhrases by tasks.registering(Copy::class) {
    from(rootProject.file("../wake/phrases.json"))
    into(generatedWakeAssets)
}

// Vosk small en-US (Apache-2.0). SHA-256 is the zip published at
// alphacephei.com for vosk-model-small-en-us-0.15.
val downloadVoskModel by tasks.registering {
    val assetsDir = layout.buildDirectory.dir("generated/vosk-assets/vosk-model").get().asFile
    val marker = File(assetsDir, "conf/model.conf")
    outputs.file(marker)
    doLast {
        if (marker.exists() && marker.length() > 0) return@doLast
        val expected = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        val zip = layout.buildDirectory.file("downloads/vosk-model-small-en-us-0.15.zip").get().asFile
        zip.parentFile.mkdirs()
        if (!zip.exists() || sha256File(zip) != expected) {
            URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
                .toURL()
                .openStream()
                .use { input -> zip.outputStream().use { output -> input.copyTo(output) } }
            val got = sha256File(zip)
            if (got != expected) {
                zip.delete()
                throw GradleException("vosk model sha256 mismatch: $got")
            }
        }
        val unpack = layout.buildDirectory.dir("vosk-unpack").get().asFile
        unpack.deleteRecursively()
        unpack.mkdirs()
        project.copy {
            from(project.zipTree(zip))
            into(unpack)
        }
        val inner = File(unpack, "vosk-model-small-en-us-0.15")
        if (!inner.isDirectory) throw GradleException("vosk zip layout unexpected")
        assetsDir.deleteRecursively()
        inner.copyRecursively(assetsDir)
        if (!marker.exists()) throw GradleException("vosk model.conf missing after unpack")
    }
}

fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

tasks.named("preBuild").configure {
    dependsOn(copyHud)
    dependsOn(copyWakePhrases)
    dependsOn(downloadVoskModel)
}
// The generated asset root is an input of merge*Assets. Wait for the
// download or the APK can ship an empty model.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyWakePhrases)
    dependsOn(downloadVoskModel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Offline wake recognizer (Apache-2.0). arm64 only, see ndk.abiFilters.
    // Replaces onnxruntime: hey_jack.onnx did not detect real speech.
    implementation("com.alphacephei:vosk-android:0.3.75")
}
