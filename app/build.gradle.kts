plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.biofe.sensorlogger"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.biofe.sensorlogger"
        // Android 10+: needed for saving exports to Downloads without storage permissions
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.9.0"
        manifestPlaceholders["appLabel"] = "Bike Sensor Logger"
    }

    // Release builds (what riders install, and what updates itself) are signed with a private key
    // that only GitHub Actions has: RELEASE_KEYSTORE is the keystore file, RELEASE_KEYSTORE_PASSWORD
    // its password. Without them the release APK comes out unsigned.
    // Test builds use a fixed debug key (committed on purpose) so they install over each other
    // without uninstalling first, which would delete rides stored on the phone.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "velowind"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // A separate app next to the release one, so trying a test build doesn't mean uninstalling
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Bike Logger (dev)"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
}
