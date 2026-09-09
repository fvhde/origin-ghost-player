import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is optional and driven by a local, gitignored keystore.properties (see README).
// Its absence must not break a fresh clone's `assembleDebug` — only assembleRelease needs it, and
// even that build still succeeds unsigned if the file is missing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    // Source/R-class package only — distinct from applicationId below, which is what the OS
    // actually sees installed.
    namespace = "com.originghostplayer.android"

    compileSdk = 36

    defaultConfig {
        // applicationId is set per product flavor below — it's the load-bearing identity spoof,
        // see docs/DEV.md. minSdk/targetSdk/version are shared: same code, same release, two
        // identities.
        minSdk = 34
        targetSdk = 36
        versionCode = 12
        versionName = "0.3.7"
    }

    flavorDimensions += "identity"
    productFlavors {
        create("kugou") {
            dimension = "identity"
            // Kugou Music Lite (酷狗音乐概念版) — the identity this was first proven on.
            applicationId = "com.kugou.android.lite"
        }
        create("luna") {
            dimension = "identity"
            // Luna Music — bigger lockscreen widget + karaoke support in OriginPlayer that
            // Kugou's identity doesn't get you. Same code otherwise.
            applicationId = "com.luna.music"
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to unsigned when no keystore.properties is present (e.g. a fresh clone),
            // so `assembleRelease` never hard-fails on a missing local secret.
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.androidx.core.ktx)
}
