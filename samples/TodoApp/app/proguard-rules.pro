# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes
-keep class io.insforge.samples.todo.data.** { *; }
-keepclassmembers class io.insforge.samples.todo.data.** {
    <init>(...);
}

# Keep InsForge SDK models
-keep class io.insforge.** { *; }
-keepclassmembers class io.insforge.** {
    <init>(...);
}

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
