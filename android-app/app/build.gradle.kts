plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "my.fouriders.scan"
    compileSdk = 35

    defaultConfig {
        applicationId = "my.fouriders.scan"
        minSdk = 30
        targetSdk = 35
        versionCode = 15
        versionName = "1.4"
        buildConfigField("String", "SUPABASE_URL", "\"https://nuebncvryoxehhnynnbb.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_Z6tTqwY3w21sMYywXT-zYg_hbM0V1oF\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
