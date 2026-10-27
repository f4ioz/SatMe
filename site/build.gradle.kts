import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The F4IOZ web site (f4ioz.fr): articles, galleries, the logbook and
 * SatMe's satellite tools (passes, Sked, Orbites, the sky), served read-only
 * behind nginx. Built on the engine (:moteur) and nothing else of SatMe: no
 * rig, no rotor, no sound, nothing that transmits. SatMe-PC publishes to it
 * (SSTV pictures, sheets, reports, contacts) through its API.
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

val versionSite = providers.gradleProperty("siteVersion").getOrElse("dev")

tasks.jar {
    manifest { attributes("Implementation-Title" to "F4IOZ site", "Implementation-Version" to versionSite) }
}

application {
    mainClass.set("fr.f4ioz.site.MainKt")
    applicationName = "f4ioz-site"
    // The JDK's own HTTP server: a request's head and body in 60 s at most, an idle connection
    // closed after 30 s (slow clients cannot hold its threads); headless (thumbnails without a screen).
    applicationDefaultJvmArgs = listOf("-Xmx320m", "-Djava.awt.headless=true",
        "-Dsun.net.httpserver.maxReqTime=60", "-Dsun.net.httpserver.maxRspTime=600",
        "-Dsun.net.httpserver.idleInterval=30", "-Dsun.net.httpserver.maxReqHeaders=64",
        "-Dsun.net.httpserver.maxIdleConnections=64")
}

dependencies {
    implementation(project(":moteur"))
    // Built into Android, added here (see :moteur).
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

// The coastlines (Natural Earth, public domain) for the globes and maps: the phone's file, taken at build time.
tasks.processResources {
    from("../app/src/main/res/raw") {
        include("land.json")
        into("fr/f4ioz/site/traits")
    }
}

// The package for the server: the program as installDist makes it, its installer, its systemd unit,
// an example configuration and the README, in f4ioz-site-kt/. The configuration itself stays outside.
val siteTar by tasks.registering(Tar::class) {
    group = "distribution"
    description = "f4ioz-site-kt.tar.gz: the site, installe-site.sh, its service and its README"
    dependsOn(tasks.installDist)
    archiveFileName.set("f4ioz-site-kt.tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("f4ioz-site-kt") {
        from("deploiement") {
            include("installe-site.sh")
            filePermissions { unix("rwxr-xr-x") }
        }
        from("deploiement") { include("f4ioz-site-kt.service", "site.conf.exemple") }
        from(".") { include("README.md") }
        into("contenu-exemple") { from("contenu-exemple") }
        into("f4ioz-site") { from(layout.buildDirectory.dir("install/f4ioz-site")) }
    }
}
