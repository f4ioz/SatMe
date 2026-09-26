# SatMe ProGuard/R8 rules for release builds.

# predict4java (com.github.amsacode fork actually used by the app).
-keep class com.github.amsacode.predict4java.** { *; }
-dontwarn com.github.amsacode.predict4java.**
# (legacy package name kept harmlessly in case of transitive use)
-dontwarn uk.me.g4dpz.satellite.**

# TAndroidLame: JNI bridge — native method names must survive shrinking.
-keep class com.naman14.androidlame.** { *; }
-dontwarn com.naman14.androidlame.**

# OkHttp / Okio (networking for GP/OMM + status). Standard safe rules.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# usb-serial-for-android: driver classes are looked up by type.
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**

# Kotlin coroutines / reflection metadata.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

# Keep data models used with JSON (org.json is reflection-free, but keep our
# model field names stable for import/export compatibility).
-keepnames class fr.f4ioz.satcombo.data.** { *; }

# Compose keeps its own rules via the AGP; nothing extra needed here.

# ————— Pour que le rapport de plantage soit lisible tel quel —————
# Depuis la 18.28, l'application écrit sa propre trace d'appels et propose de
# l'envoyer par courrier. Obfusquée, cette trace ne vaut rien sans le fichier
# de correspondance de la version exacte qui a planté — et ce fichier fait
# cinquante méga-octets, qu'il faudrait archiver puis retrouver, version par
# version, des mois plus tard. Une seule perte, et le rapport devient un
# alignement de « a.b.c ».
# On garde donc les noms de nos propres classes. Le retrait du code mort et
# les optimisations continuent (`allowshrinking, allowoptimization`) : seuls
# les noms survivent. Le paquet grossit un peu ; une trace qui se lit à l'œil
# nu, six mois après, sans rien d'autre que le courrier reçu, vaut ce prix.
-keepattributes SourceFile,LineNumberTable
-keep,allowshrinking,allowoptimization class fr.f4ioz.satcombo.** { *; }
