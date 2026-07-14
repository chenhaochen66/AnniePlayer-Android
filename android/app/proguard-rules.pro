# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# react-native-reanimated
-keep class com.swmansion.reanimated.** { *; }
-keep class com.facebook.react.turbomodule.** { *; }

# Hermes / JNI
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }

# Expo native modules
-keep class expo.modules.** { *; }

# React Native core
-keep class com.facebook.react.** { *; }

# OkHttp (used by fetch/network)
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep all NativeModules (including your custom media-scanner)
-keep class * extends com.facebook.react.bridge.NativeModule { *; }
-keep class * extends com.facebook.react.bridge.ReactContextBaseJavaModule { *; }

# Keep JS interfaces
-keepattributes *Annotation*
-keep class * extends java.lang.annotation.Annotation { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep R (resources)
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Add any project specific keep options here:
