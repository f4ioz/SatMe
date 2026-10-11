import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * SatMe's engine: everything that runs without Android — orbits, Doppler,
 * decoders, rig and rotator protocols, the web server. The Android app and
 * the PC version are built on it.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    // Test helpers (a synthetic SSTV signal…) shared with the app's tests.
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    api("com.github.davidmoten:predict4java:1.3.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
