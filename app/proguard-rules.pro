# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.stephenjoy.recipes.**$$serializer { *; }
-keepclassmembers class com.stephenjoy.recipes.** {
    *** Companion;
}
-keepclasseswithmembers class com.stephenjoy.recipes.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Credential Manager / Google ID
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# PdfBox-Android optional JPEG2000 codec
-dontwarn com.gemalto.jp2.**

# Jsoup optional deps
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
