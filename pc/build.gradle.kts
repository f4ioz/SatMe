import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * SatMe on a PC (Linux, Windows, Raspberry Pi): the engine, the PC's serial
 * ports and a page in the browser. Nothing here is built into the Android app.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("fr.f4ioz.satcombo.pc.MainKt")
    applicationName = "satme"
    // Enough for the passes of a hundred satellites; small enough for a Raspberry Pi.
    applicationDefaultJvmArgs = listOf("-Xmx256m")
}

dependencies {
    implementation(project(":moteur"))
    // Built into Android, added here (see :moteur).
    implementation("org.json:json:20240303")
    // The PC's serial ports, on Linux, Windows and macOS (LGPL-3 / Apache-2).
    implementation("com.fazecast:jSerialComm:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":moteur")))
}
