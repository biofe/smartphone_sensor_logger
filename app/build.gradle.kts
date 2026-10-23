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
        versionCode = 7
        versionName = "0.7.0"
    }

    // A fixed debug key (committed on purpose) so CI builds can be installed over each other
    // without uninstalling first, which would delete rides stored on the phone.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
