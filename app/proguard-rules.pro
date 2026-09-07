# ProGuard rules for Pyramid Relay
# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# instruction in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep the application class
-keep class com.pyramidrelay.** { *; }

# Keep Room entities
-keep class com.pyramidrelay.BroadcastEntity { *; }
-keep class com.pyramidrelay.SubscriptionEntity { *; }

# Keep RSA keys
-keep class java.security.KeyStore { *; }
-keep class java.security.PrivateKey { *; }
-keep class java.security.PublicKey { *; }

# ML Kit - keep all internal classes and their members
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.mlkit.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Google Play Services / Dagger dependency injection used by ML Kit
-keep class dagger.** { *; }
-keep class * extends dagger.internal.Factory { *; }
-dontwarn dagger.**

# Markwon (Markdown rendering) — uses reflection for plugins/spans
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# Bouncy Castle (crypto provider used by Tink) — provider/reflection based
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Google Tink (hybrid encryption) — self-registers primitives via reflection
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
