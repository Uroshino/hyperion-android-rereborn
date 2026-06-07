# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep crash line numbers useful while still obfuscating.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- FlatBuffers protocol classes ---
# The hyperionnet.* classes are generated from Hyperion's flatbuffer schema and are
# constructed/encoded reflection-free, but keep them (and the flatbuffers runtime) intact so
# field offsets and the wire format are never altered by optimisation.
-keep class hyperionnet.** { *; }
-keep class com.google.flatbuffers.** { *; }

# --- Leanback ---
# Leanback inflates GuidedStep / presenter classes by name in places; keep them and their views.
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# --- Screen-capture service/encoder: touched via framework callbacks; keep intact. ---
-keep class com.hyperion.grabber.common.HyperionScreenService { *; }
-keep class com.hyperion.grabber.common.HyperionScreenEncoder { *; }
