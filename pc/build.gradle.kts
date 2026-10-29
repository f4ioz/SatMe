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

// The version a release gives (its tag "satme-pc-2026.10.13" → 2026.10.13); "dev" when built by hand.
// In the jar's manifest only: the project's own version would rename the package
// (satme-2026.10.13.tar), and the guides and SatMe-Node's script expect satme.tar → satme/.
val versionPc = providers.gradleProperty("satmePcVersion").getOrElse("dev")

tasks.jar {
    manifest { attributes("Implementation-Title" to "SatMe-PC", "Implementation-Version" to versionPc) }
}

application {
    mainClass.set("fr.f4ioz.satcombo.pc.MainKt")
    applicationName = "satme"
    // Enough for the passes of a hundred satellites; small enough for a Raspberry Pi.
    // Native access for the serial ports' library (jSerialComm), said aloud: Java 24+ warns otherwise
    // and will refuse it one day; Java 17 accepts the option too.
    applicationDefaultJvmArgs = listOf("-Xmx256m", "--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":moteur"))
    // Built into Android, added here (see :moteur).
    implementation("org.json:json:20240303")
    // The PC's serial ports, on Linux, Windows and macOS (LGPL-3 / Apache-2).
    implementation("com.fazecast:jSerialComm:2.11.0")
    // The QR code the phone scans to join the PC (Apache 2.0), as the phone draws its own.
    implementation("com.google.zxing:core:3.5.3")
    // The event's report as a PDF, drawn without a browser (Apache 2.0; its fontbox and commons-logging with it).
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // The SSTV pictures' « IA » (MIT): compiled against, never shipped — its 93 Mo jar is downloaded when the
    // owner installs the model (IaSstvPc), and loaded beside SatMe.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")

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

// « Quoi de neuf » and the update's notes: pc/NOUVEAUTES.md (one section per version, FR and EN), bundled as written.
tasks.processResources {
    from(".") {
        include("NOUVEAUTES.md")
        into("nouveautes")
    }
}

// The demonstration's package (`satme --demo` as a service on a Linux VM or a Raspberry Pi):
// the program as installDist makes it, its installer and its notes, in satme-demo/.
val demoTar by tasks.registering(Tar::class) {
    group = "distribution"
    description = "satme-demo.tar.gz: SatMe-PC, installe-demo.sh and its README"
    dependsOn(tasks.installDist)
    archiveFileName.set("satme-demo.tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("satme-demo") {
        from("demo") {
            include("installe-demo.sh")
            filePermissions { unix("rwxr-xr-x") }
        }
        from("demo") { include("README.md") }
        into("satme") { from(layout.buildDirectory.dir("install/satme")) }
    }
}

// The PDF manuals (SatMe-manuel-fr.pdf, SatMe-manuel-en.pdf in build/manuel): the guide docs/station-pc*.md laid out
// for printing (ManuelImprime, in :moteur) and printed by a headless Chrome or Chromium (CHROME=… to choose one).
// A source set of its own: nothing of it goes into SatMe-PC. Without a browser, only the HTML (exit code 2).
val manuel by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets["main"].runtimeClasspath
}
val manuelPdf by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "The PDF manuals of SatMe-PC (FR and EN) in build/manuel, printed by a headless Chrome or Chromium"
    classpath = manuel.runtimeClasspath
    mainClass.set("fr.f4ioz.satcombo.pc.manuel.ManuelPdfKt")
    val docs = rootProject.file("docs")
    val sortie = layout.buildDirectory.dir("manuel")
    inputs.files(fileTree(docs) { include("station-pc*.md", "images/**") })
    inputs.property("version", versionPc)
    outputs.dir(sortie)
    args(docs.path, sortie.get().asFile.path, versionPc)
    providers.gradleProperty("manuelLangues").orNull?.let { args(it) }
}
