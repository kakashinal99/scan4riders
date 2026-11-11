plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "my.fouriders.scan"
    compileSdk = 34

    defaultConfig {
        applicationId = "my.fouriders.scan"
        minSdk = 30
        targetSdk = 34
        versionCode = 13
        versionName = "1.2"
        buildConfigField("String", "SUPABASE_URL", "\"https://nuebncvryoxehhnynnbb.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_Z6tTqwY3w21sMYywXT-zYg_hbM0V1oF\"")
        buildConfigField("String", "CUSTOM_MAP_URL", "\"https://fourriders-custom-map.onrender.com\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // On-device text reading (free, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
