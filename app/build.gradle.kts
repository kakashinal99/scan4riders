plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "my.fouriders.scan"
    compileSdk = 34

    defaultConfig {
        applicationId = "my.fouriders.scan"
        minSdk = 30
        targetSdk = 34
        versionCode = 9
        versionName = "0.9"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // On-device text reading (free, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
