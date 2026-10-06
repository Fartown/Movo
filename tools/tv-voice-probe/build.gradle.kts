plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
}
android {
    namespace = "io.github.fartown.movo.tvvoiceprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.fartown.movo.tvvoiceprobe"
        minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "P0"
        ndk { abiFilters += "armeabi-v7a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug"); isDebuggable = true } }
    packaging { jniLibs { excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so") } }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("probe-assets"))
}
val copyProbeAssets by tasks.registering(Sync::class) {
    from("../../app/src/main/assets") {
        include("sherpa-kws/encoder.int8.onnx", "sherpa-kws/decoder.onnx", "sherpa-kws/joiner.int8.onnx", "sherpa-kws/tokens.txt", "voice/aec.model")
    }
    into(layout.buildDirectory.dir("probe-assets"))
}
tasks.named("preBuild").configure { dependsOn(copyProbeAssets) }
dependencies {
    implementation("com.k2fsa:sherpa-onnx:1.13.8@aar")
    implementation("com.bytedance.speechengine:speechengine_tob:0.0.15.0@aar")
}
