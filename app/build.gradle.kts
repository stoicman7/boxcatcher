plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bagcatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bagcatcher"
        minSdk = 26
        // Target 28 to keep the first release compatible with Android 8.1
        // without introducing newer runtime permission requirements.
        targetSdk = 28
        versionCode = 3
        versionName = "1.2-learning"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
