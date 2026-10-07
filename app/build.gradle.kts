import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode follows the number of commits, so every build is installable as
// an update of the previous one.
fun gitVersionCode(): Int {
    return try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(project.rootDir)
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        text.toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

// Optional distribution signing (a fixed keystore passed by CI through the
// environment, so consecutive builds can update each other). Without it the
// regular debug signature is used.
val distributionKeystore: File? = System.getenv("WEBSREEN_KEYSTORE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.webscreen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webscreen.app"
        minSdk = 26
        targetSdk = 34
        versionCode = gitVersionCode()
        versionName = "1.0.0"
    }

    signingConfigs {
        val keystore = distributionKeystore
        if (keystore != null) {
            create("distribution") {
                storeFile = keystore
                storePassword = System.getenv("WEBSREEN_KEYSTORE_PASSWORD") ?: "webscreen"
                keyAlias = System.getenv("WEBSREEN_KEY_ALIAS") ?: "webscreen"
                keyPassword = System.getenv("WEBSREEN_KEY_PASSWORD") ?: "webscreen"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("distribution")
                ?: signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("distribution")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        ndk {
            // Phase 1 targets 64 bit ARM devices (the Go binary is built for
            // android/arm64 only).
            abiFilters += "arm64-v8a"
        }
    }

    // The Go binary is shipped as libwebscreen.so and must exist as a real file
    // on disk (android:extractNativeLibs) so that it can be copied to
    // /data/local/tmp and executed as root.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "so"
    }
}
