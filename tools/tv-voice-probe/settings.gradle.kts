pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/sherpa-onnx-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.k2fsa", "sherpa-onnx") }
        }
        maven {
            url = uri("https://artifact.bytedance.com/repository/Volcengine/")
            content { includeGroup("com.bytedance.speechengine") }
        }
    }
}
rootProject.name = "MovoTvVoiceProbe"
