plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val usbSelfTest = providers.gradleProperty("usbSelfTest").orNull == "true"
if (usbSelfTest) layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".tools/usb-selftest-build"))
android {
    namespace = "com.localmusic.diagnostics"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = if (usbSelfTest) "com.localmusic.diagnostics.selftest" else "com.localmusic.diagnostics"
        buildConfigField("boolean", "USB_SELF_TEST", usbSelfTest.toString())
        testInstrumentationRunner = "com.localmusic.diagnostics.UsbRuntimeInstrumentation"
        minSdk = 30
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0-media3-direct"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DUSB_SELF_TEST=$usbSelfTest") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = rootProject.file("native-usb/CMakeLists.txt"); version = "3.22.1" } }
    if (usbSelfTest) sourceSets.getByName("main").manifest.srcFile("src/selftest/AndroidManifest.xml")
    sourceSets.getByName("main").assets.srcDir(rootProject.file(if (usbSelfTest) ".tools/source-session-fixtures" else ".tools/fixtures"))
    sourceSets.getByName("main").assets.srcDir(rootProject.file("native-usb/notices"))
    sourceSets.getByName("main").java.srcDir(rootProject.file("direct-usb/src/main/java"))
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".tools/debug.keystore")
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation(project(":usb-core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
