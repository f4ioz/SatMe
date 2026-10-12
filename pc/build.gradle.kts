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
    // The QR code the phone scans to join the PC (Apache 2.0), as the phone draws its own.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":moteur")))
}

// The coastlines and borders (Natural Earth, public domain) drawn on the METEOR
// pictures: the phone's files, taken at build time rather than copied.
tasks.processResources {
    from("../app/src/main/res/raw") {
        include("land.json", "borders.json")
        into("traits")
    }
}

// The app's icon for the "SatMe" mark on every export: the repository's, taken at build time.
tasks.processResources {
    from("../docs/images") {
        include("icon.png")
        into("marque")
    }
}
