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
            // Robolectric lit le manifeste et les ressources fusionnées : sans
            // cela il démarre sur une application vide et R.raw reste
            // introuvable — donc pas de base interne d'indicatifs.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    defaultConfig {
        applicationId = "fr.f4ioz.satcombo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2167
        versionName = "20.67"
    }

    // Un AAB est servi en morceaux : le tronc, plus un module par langue, par
    // densité d'écran et par jeu d'instructions. C'est plus léger à télécharger
    // — et c'est aussi une pièce mobile de plus. Certains installeurs de
    // constructeurs en égarent un, et l'application meurt alors sur un
    // `Resources$NotFoundException` avant sa première ligne de code : rien à
    // déboguer, rien dans les journaux, juste une application qui ne s'ouvre
    // pas chez celui-là et s'ouvre chez tous les autres. On renonce donc au
    // découpage. Le paquet grossit de quelques centaines de kilo-octets ; on
    // les échange volontiers contre un démarrage qui ne dépend plus de la
    // bonne foi de l'installeur.
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

        // Le type « echec » est une release ordinaire, au bit près, PLUS une
        // seconde icône de lancement qui ouvre le mode échec (voir
        // src/echec/AndroidManifest.xml). Même identifiant d'application et
        // même clé de signature que la release : c'est la condition pour qu'il
        // s'installe PAR-DESSUS une copie qui plante sans la désinstaller —
        // désinstaller effacerait le dossier privé, donc le rapport de plantage
        // qu'on est précisément venu chercher.
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

// Le banc de mesure radiosonde ne tourne que sur demande : ses balayages
// prennent plusieurs minutes, ce qui n'a pas sa place dans une compilation
// ordinaire. On le réveille par `gradle testDebugUnitTest -Dsatme.bench=1`, et
// l'on montre alors la sortie standard, faute de quoi le tableau de mesures
// finirait dans un rapport HTML que personne ne va lire.
tasks.withType<Test>().configureEach {
    val bench = System.getProperty("satme.bench") ?: ""
    systemProperty("satme.bench", bench)
    maxHeapSize = "2g"
    if (bench.isNotEmpty()) {
        testLogging { showStandardStreams = true }
    }
}

dependencies {
    // Le générateur de QR code du mode démonstration. Licence Apache 2.0,
    // compatible avec la distribution de SatMe ; le cœur seul, sans la partie
    // Android qui apporterait une dépendance à la caméra dont on n'a pas besoin.
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
    // Robolectric : un Android en mémoire, sur la JVM.
    //
    // Les essais de domaine ne voient pas le démarrage, et c'est de là que
    // viennent les trois dernières régressions livrées — la mémoire du clavier
    // perdue en 19.11, notamment. Il faut un SharedPreferences, un Application
    // et des ressources pour instancier le ViewModel : Robolectric les fournit
    // sans appareil ni émulateur.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Le ViewModel programme le rafraîchissement des TLE dès sa construction ;
    // hors appareil, WorkManager doit être initialisé à la main.
    testImplementation("androidx.work:work-testing:2.9.1")
}
