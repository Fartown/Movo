plugins {
    id("com.android.application") version "9.3.2"
}
android {
    namespace = "io.github.fartown.movo.mediaprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.fartown.movo.mediaprobe"
        minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
