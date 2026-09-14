plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zenith.engine"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zenith.engine"
        minSdk = 29 // Android 10+ required for Snapdragon QNN / NNAPI 1.3+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Target Snapdragon 64-bit ARM architectures and x86_64 emulator environments
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    // Do not compress ONNX models in APK assets for faster mmap loading
    androidResources {
        noCompress.add("onnx")
    }
}

dependencies {
    // ONNX Runtime Android
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Google ML Kit Text Recognition (On-Device OCR & Vision Intelligence)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ZXing QR Code Generation for Instant Pairing
    implementation("com.google.zxing:core:3.5.3")

    // High-Throughput Embedded WebSocket Server for Telemetry
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Embedded Ktor Server for Web Dashboard & Multi-Device Streaming
    implementation("io.ktor:ktor-server-core:2.3.10")
    implementation("io.ktor:ktor-server-cio:2.3.10")
    implementation("io.ktor:ktor-server-websockets:2.3.10")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // AndroidX & Architecture Components
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("com.google.android.material:material:1.12.0")
}
