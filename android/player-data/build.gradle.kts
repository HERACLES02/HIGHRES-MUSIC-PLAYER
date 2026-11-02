plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp") version "2.3.4"
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    sourceSets {
        commonMain.dependencies {
            implementation("androidx.room:room-runtime:2.8.4")
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        androidMain {
            kotlin.srcDir("src/sharedMain/kotlin")
            dependencies { implementation(project(":player-core")); implementation("com.squareup.okhttp3:okhttp:5.1.0") }
        }
        jvmMain {
            kotlin.srcDir("src/sharedMain/kotlin")
            dependencies { implementation(project(":player-core")); implementation("com.squareup.okhttp3:okhttp:5.1.0") }
        }
    }
}
android {
    namespace = "com.localmusic.data"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.8.4")
    add("kspJvm", "androidx.room:room-compiler:2.8.4")
    add("kspJvmTest", "androidx.room:room-compiler:2.8.4")
}
ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
    arg("room.generateKotlin", "true")
}
tasks.register<JavaExec>("selfTest") {
    dependsOn("jvmTestClasses")
    val test = kotlin.targets.getByName<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath = test.output.allOutputs + test.runtimeDependencyFiles
    mainClass.set("com.localmusic.data.PersistenceChecksKt")
}
