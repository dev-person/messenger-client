plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.secure.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.secure.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 68
        versionName = "1.0.68 alfa"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../grizzly.keystore")
            storePassword = "3WgsBGNMmiQw66GxYqEeLiCw"
            keyAlias = "grizzly"
            keyPassword = "3WgsBGNMmiQw66GxYqEeLiCw"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL",  "\"https://grizzly-messenger.ru/v1/\"")
            buildConfigField("String", "WS_BASE_URL",   "\"wss://grizzly-messenger.ru/ws\"")
        }
        debug {
            isDebuggable = true
            buildConfigField("String", "API_BASE_URL",  "\"https://grizzly-messenger.ru/v1/\"")
            buildConfigField("String", "WS_BASE_URL",   "\"wss://grizzly-messenger.ru/ws\"")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle requires this exclusion
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.core)
    ksp(libs.moshi.codegen)

    // Coroutines
    implementation(libs.coroutines.android)

    // WebRTC (DTLS-SRTP built-in) + Compose video renderers
    implementation(libs.webrtc)
    implementation(libs.webrtc.compose)

    // Cryptography (E2E: X25519, AES-256-GCM, HKDF)
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pkix)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security (EncryptedSharedPreferences)
    implementation(libs.security.crypto)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Permissions
    implementation(libs.accompanist.permissions)

    // On-device Generative AI (Gemini Nano)
    implementation(libs.generativeai)

    // Logging
    implementation(libs.timber)

    // Firebase (push-уведомления через FCM)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
