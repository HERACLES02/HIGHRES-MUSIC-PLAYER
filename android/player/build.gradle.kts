plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.localmusic.player"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.localmusic.player"
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "com.localmusic.player.AudioProcessorChecks"
        versionCode = 49
        versionName = "0.14.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") { versionNameSuffix = "-debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-preview.pro")
        }
        create("peerTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".peertest"
            versionNameSuffix = "-peer-test"
            matchingFallbacks += listOf("debug")
        }
        create("preview") {
            initWith(getByName("release"))
            versionNameSuffix = "-optimized-preview"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-preview.pro")
        }
        // Separate package so generating the baseline profile never touches the installed app or its data.
        // Unminified so the profile names real classes; R8 rewrites them for release.
        create("profileGen") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profilegen"
            versionNameSuffix = "-profilegen"
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = rootProject.file("native-tags/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("native-tags/notices"))
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".tools/debug.keystore") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":player-core"))
    implementation(project(":player-data"))
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
