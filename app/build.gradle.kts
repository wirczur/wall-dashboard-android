plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.walldashboard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.walldashboard.app"
        minSdk = 29          // Android 10+ (Grenton S6PE: Android 11)
        targetSdk = 30
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // signed with the debug key, so a release build installs without setting up a keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false }
}
// no extra libraries: plain Android framework + Kotlin standard library
