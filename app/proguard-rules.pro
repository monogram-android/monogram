# R8 / release keep rules.

-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions,RuntimeVisibleAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile
-keep class kotlin.Metadata { *; }

-keep class org.monogram.BuildConfig { *; }
-keepclassmembers class org.monogram.BuildConfig {
    public static <fields>;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlinx
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class org.monogram.**$$serializer { *; }
-keepclassmembers class org.monogram.** {
    *** Companion;
}
-keepclasseswithmembers class org.monogram.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation / stores
-keep class com.arkivanov.decompose.** { *; }
-keep class com.arkivanov.essenty.** { *; }
-keep class com.arkivanov.mvikotlin.** { *; }
-dontwarn com.arkivanov.**

# JNA + UniFFI + native facade
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }
-keep class org.monogram.mtproto.** { *; }
-keep class org.monogram.network.bridge.** { *; }
-dontwarn java.awt.**
-dontwarn android.app.Fragment

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Media3 extractors are looked up reflectively.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-dontwarn androidx.compose.**
