plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pixel10.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixel10.ai"
        minSdk = 31
        targetSdk = 35
        versionCode = 7
        versionName = "1.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // ML Kit GenAI — Gemini Nano via AICore (recommended for Pixel 10)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")

    // MediaPipe LLM Inference — legacy fallback for .task/.bin models
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // LiteRT-LM — primary backend for Gemma 3n .litertlm models
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.9.0-alpha05")

    // Embedded HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
