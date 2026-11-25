plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val experimentalDirectUsb=providers.gradleProperty("experimentalDirectUsb").orNull=="true"
val soulseek=providers.gradleProperty("soulseek").orNull=="true"
android {
    namespace = "com.localmusic.player"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.localmusic.player"
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "com.localmusic.player.AudioProcessorChecks"
        buildConfigField("boolean", "PUBLIC_SOULSEEK", "false")
        versionCode = 52
        versionName = if(experimentalDirectUsb)"0.14.4-direct-coverage" else "0.14.4"
        buildConfigField("boolean","DIRECT_USB_ENABLED",experimentalDirectUsb.toString())
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake {
            arguments += "-DANDROID_STL=c++_static"
            arguments += "-DLOCALMUSIC_EXPERIMENTAL_DIRECT=${if(experimentalDirectUsb) "ON" else "OFF"}"
            targets += listOf("localmusic_tags","usb_experiment","usb-1.0")
        } }
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
            manifestPlaceholders["peerAppLabel"] = "Local Music Peer Test"
            manifestPlaceholders["peerDiagnosticsEnabled"] = "true"
        }
        create("peerPublicTest") {
            initWith(getByName("peerTest"))
            applicationIdSuffix = ".peertest.publictest"
            versionNameSuffix = "-peer-public-test"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "PUBLIC_SOULSEEK", "true")
            manifestPlaceholders["peerAppLabel"] = "Local Music Public Peer Test"
            manifestPlaceholders["peerDiagnosticsEnabled"] = "false"
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
    // Soulseek appears only when its native library is packaged (SoulseekFixture.available).
    // The normal app leaves it out unless built with -Psoulseek=true; Peer Test builds always carry it.
    val peerLibs = rootProject.file(".tools/peer-test/jniLibs")
    (if (soulseek) listOf("main") else listOf("peerTest", "peerPublicTest"))
        .forEach { sourceSets.getByName(it).jniLibs.srcDir(peerLibs) }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("native-usb/notices"))
    sourceSets.getByName("main").java.srcDir(rootProject.file("direct-usb/src/main/java"))
    sourceSets.getByName("peerPublicTest") {
        manifest.srcFile("src/peerTest/AndroidManifest.xml")
        java.srcDir("src/peerTest/java")
    }
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".tools/debug.keystore") }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("peerPublicTest")) { variant ->
    variant.packaging.jniLibs.excludes.add("**/armeabi-v7a/**")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":player-core"))
    implementation(project(":player-data"))
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
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
