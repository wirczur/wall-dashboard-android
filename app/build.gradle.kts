import java.util.Properties

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
        versionCode = 3
        versionName = "0.1.2"
    }

    // one fixed key for every build (Android only installs an update signed with the same key)
    val signProps = Properties().apply {
        val f = rootProject.file("signing/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("wall") {
            storeFile = file(signProps.getProperty("storeFile", "../signing/walldashboard.jks"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias", "walldashboard")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wall")
        }
        debug {
            // Android Studio builds use the same key, so they install over a release from GitHub and vice versa
            signingConfig = signingConfigs.getByName("wall")
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
