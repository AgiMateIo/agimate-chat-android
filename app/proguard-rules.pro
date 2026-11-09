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

# JS-мост запасного канала: страница зовёт метод `post` по имени, и R8 о такой связи не знает.
# Общий proguard-android.txt такие методы уже держит, но правило здесь делает границу явной и
# локальной. Имя класса — только якорь, по которому правило находят до сжатия; странице видно имя
# метода. Пропажа `post` проявилась бы только в release-сборке.
-keepclassmembers class ru.agimate.mobile.core.views.ViewBridge$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp/Centrifugo тянут опциональные классы, которых в рантайме нет.
-dontwarn okhttp3.internal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
