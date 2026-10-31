import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * TellusMe — the SatMe ecosystem's terrestrial station (HF, later VHF/UHF):
 * a draft, receive only. SatMe's engine, SatMe-PC's serial ports and storage,
 * and a page of its own in the browser, on http://localhost:8092/.
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

val versionTellus = providers.gradleProperty("tellusmeVersion").getOrElse("dev")

tasks.jar {
    manifest { attributes("Implementation-Title" to "TellusMe", "Implementation-Version" to versionTellus) }
}

application {
    mainClass.set("fr.f4ioz.satcombo.tellus.MainKt")
    applicationName = "tellusme"
    applicationDefaultJvmArgs = listOf("-Xmx192m", "--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":moteur"))
    // SatMe-PC's serial ports (jSerialComm) and its settings file, reused rather than copied.
    implementation(project(":pc"))
    implementation("org.json:json:20240303")
    implementation("com.fazecast:jSerialComm:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
