import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.f4ioz.satcombo"
    compileSdk = 36
    // Needed to strip the (unstripped) vendored libandroidlame.so for the APK
    // and to extract native debug symbols into the AAB (Play console warning).
    ndkVersion = "28.2.13676358"

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources; without them
            // it starts on an empty app and R.raw (the built-in callsign
            // database) is missing.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    defaultConfig {
        applicationId = "fr.f4ioz.satcombo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2178
        versionName = "20.78"
    }

    // No AAB splits. Some vendor installers drop a language/density/ABI split,
    // and the app then dies on `Resources$NotFoundException` before its first
    // line of code, with nothing in the logs. A few hundred KB more is worth a
    // startup that doesn't depend on the installer.
    bundle {
        language { enableSplit = false }
        density { enableSplit = false }
        abi { enableSplit = false }
    }

    // Release signing. Keys are read from keystore.properties (NOT committed) or
    // from environment variables, so the keystore never lives in source control.
    // Create keystore.properties in the project root with:
    //   storeFile=/absolute/path/f4ioz-release.jks
    //   storePassword=...
    //   keyAlias=satme
    //   keyPassword=...
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile")
                ?: System.getenv("SATME_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("SATME_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("SATME_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("SATME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Package native symbol tables in the AAB so Play can symbolicate
            // native crashes/ANRs (removes the "no debug symbols" warning).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            // Use the release signing config only when a keystore is configured.
            if (rootProject.file("keystore.properties").exists() ||
                System.getenv("SATME_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // "echec" is a bit-for-bit release build PLUS a second launcher icon
        // that opens failure mode (see src/echec/AndroidManifest.xml). Same
        // application ID and signing key as release, so it installs OVER a
        // crashing copy: uninstalling would wipe the private folder, and with
        // it the crash report we came for.
        create("echec") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-echec"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// The radiosonde benchmark runs only on demand (its sweeps take minutes):
// `gradle testDebugUnitTest -Dsatme.bench=1`. Stdout is shown then, otherwise
// the results table ends up in an HTML report nobody reads.
tasks.withType<Test>().configureEach {
    val bench = System.getProperty("satme.bench") ?: ""
    systemProperty("satme.bench", bench)
    // APRS decoder against real recordings: `-Dsatme.aprs=file1.wav,file2.wav`.
    val aprs = System.getProperty("satme.aprs") ?: ""
    systemProperty("satme.aprs", aprs)
    // KISS radio on the PC's serial port: `-Dsatme.kiss=/dev/ttyUSB0` (receive only).
    systemProperty("satme.kiss", System.getProperty("satme.kiss") ?: "")
    // TH-D72 as the CAT rig on the PC's serial port: `-Dsatme.thd72=/dev/ttyUSB0` (no transmit).
    systemProperty("satme.thd72", System.getProperty("satme.thd72") ?: "")
    // FT3D waypoint output on the PC's serial port: `-Dsatme.ft3d=/dev/ttyUSB1` (listening only).
    systemProperty("satme.ft3d", System.getProperty("satme.ft3d") ?: "")
    systemProperty("satme.ft3d.ecoute", System.getProperty("satme.ft3d.ecoute") ?: "120")
    systemProperty("satme.kissbrut", System.getProperty("satme.kissbrut") ?: "")
    // Transmits one frame through the KISS radio: only with `-Dsatme.kiss.emission=OUI` too.
    systemProperty("satme.kiss.emission", System.getProperty("satme.kiss.emission") ?: "")
    systemProperty("satme.kiss.chemin", System.getProperty("satme.kiss.chemin") ?: "")
    systemProperty("satme.kiss.numero", System.getProperty("satme.kiss.numero") ?: "1")
    systemProperty("satme.kiss.puissance", System.getProperty("satme.kiss.puissance") ?: "1")
    systemProperty("satme.kiss.ecoute", System.getProperty("satme.kiss.ecoute") ?: "30")
    systemProperty("satme.kiss.position", System.getProperty("satme.kiss.position") ?: "")
    systemProperty("satme.kiss.dest", System.getProperty("satme.kiss.dest") ?: "F4IOZ")
    maxHeapSize = "2g"
    if (bench.isNotEmpty() || aprs.isNotEmpty()) {
        testLogging { showStandardStreams = true }
    }
}

dependencies {
    // QR codes for demo mode (Apache 2.0). Core only: the Android module would
    // pull in a camera dependency we don't need.
    implementation("com.google.zxing:core:3.5.3")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Explicit modern Fragment: required by the ActivityResult APIs (lint vital);
    // otherwise an ancient transitive fragment version fails the release build.
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    // SAF folder access for the user-chosen recordings export directory.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // SGP4 / orbital propagation (predict4java)
    implementation("com.github.davidmoten:predict4java:1.3.1")
    // USB-serial for CAT control (CP210x/FTDI/CH34x) over USB-C OTG.
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    // TLE download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MP3 encoding (LAME): vendored. The Java wrapper (com.naman14.androidlame,
    // LGPL) lives in src/main/java and libandroidlame.so in src/main/jniLibs,
    // rebuilt from TAndroidLame sources with NDK r28c and 16 KB page alignment
    // (-z max-page-size=16384) — required by Play for Android 15+ devices. The
    // old JitPack artifact (NDK ~2016, 4 KB pages) triggered a Play warning.

    // Fused location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Background pass notifications
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // In-app updates (flexible flow). Only answers on Store-installed builds;
    // a side-loaded APK gets an error that PlayUpdater swallows.
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // Unit tests (JVM): orbital maths, grid conversions, sked interpolation.
    testImplementation("junit:junit:4.13.2")
    // Robolectric: an in-memory Android on the JVM. Domain tests don't cover
    // startup, where the last three shipped regressions came from (e.g. the
    // keypad memory lost in 19.11). The ViewModel needs SharedPreferences, an
    // Application and resources; Robolectric provides them without a device.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // The ViewModel schedules the TLE refresh on construction; off-device,
    // WorkManager must be initialized by hand.
    testImplementation("androidx.work:work-testing:2.9.1")
}
