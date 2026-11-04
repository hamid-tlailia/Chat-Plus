# Keep generic signatures + Kotlin metadata: kotlinx.serialization / Ktor resolve serializers with
# typeOf<T>() at runtime (e.g. List<T> in supabase-kt). R8 full mode strips these by default and
# reflection then fails with "Unresolved class: interface java.util.List".
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# kotlinx.serialization — keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class app.zura.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.zura.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp platform probes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
