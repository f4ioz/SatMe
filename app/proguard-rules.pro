# SatMe ProGuard/R8 rules for release builds.

# predict4java: the com.github.davidmoten artifact ships the
# com.github.amsacode.predict4java package.
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

# ————— Keep crash reports readable as-is —————
# Since 18.28 the app writes its own stack trace and offers to email it. An
# obfuscated trace is useless without the exact version's mapping file (~50 MB,
# to be archived and found again months later); lose one and the report is a
# row of "a.b.c". So our own class names are kept; shrinking and optimization
# still apply (`allowshrinking, allowoptimization`). A slightly bigger package
# is worth a trace that reads on its own from the email alone.
-keepattributes SourceFile,LineNumberTable
-keep,allowshrinking,allowoptimization class fr.f4ioz.satcombo.** { *; }
