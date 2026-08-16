# kotlinx.serialization: сериализаторы находятся по аннотациям, R8 их не видит.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ru.agimate.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class ru.agimate.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit держит generic-сигнатуры интерфейсов через рефлексию.
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface ru.agimate.mobile.core.network.api.**

# OkHttp/Centrifugo тянут опциональные классы, которых в рантайме нет.
-dontwarn okhttp3.internal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
