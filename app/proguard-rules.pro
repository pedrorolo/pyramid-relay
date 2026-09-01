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
