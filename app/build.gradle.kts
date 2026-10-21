plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.carmirror"
    // 설치된 최신 SDK에 맞춰 올려도 된다 (예: Android 17 SDK가 설치되어 있으면 37).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.carmirror"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // 고정 디버그 키: PC/GitHub Actions 어디서 빌드해도 같은 서명이라 덮어쓰기 설치가 된다
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

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    // 안드로이드 오토(폰 연결형)용 호스트 지원
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
}
