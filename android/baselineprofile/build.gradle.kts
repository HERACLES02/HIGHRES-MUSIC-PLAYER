plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}
// Generates player/src/main/baseline-prof.txt; run it with scripts/generate-baseline-profile.ps1.
android {
    namespace = "com.localmusic.baselineprofile"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        create("profileGen") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".tools/debug.keystore") }
    targetProjectPath = ":player"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
androidComponents { beforeVariants(selector().withBuildType("debug")) { it.enable = false } }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test.ext:junit:1.3.0")
}
