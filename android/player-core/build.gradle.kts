plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

tasks.register<JavaExec>("selfTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.localmusic.core.CoreChecksKt")
}
tasks.check { dependsOn("selfTest") }

dependencies {
    // Offline Japanese word splitting and readings for romaji (Apache-2.0).
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
}
