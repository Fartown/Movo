plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}
android {
    namespace = "io.github.fartown.movo.tvcomposeprobe"
    compileSdk = 36
    defaultConfig { applicationId = "io.github.fartown.movo.tvcomposeprobe"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "P0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
}
