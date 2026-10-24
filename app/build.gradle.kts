plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drasticra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drasticra"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "0.1.1"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
