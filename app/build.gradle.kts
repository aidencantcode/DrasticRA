plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drasticra"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.drasticra"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "0.2.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("dsra") {
            storeFile = file("dsra.keystore")
            storePassword = "dsra1234"
            keyAlias = "dsra"
            keyPassword = "dsra1234"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("dsra") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dsra")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
