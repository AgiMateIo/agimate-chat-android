// Встроенный в AGP 9.3.1 Kotlin — 2.2.10, а coil и markdown-renderer уже собраны Kotlin 2.4:
// компилятор 2.2 не читает их метаданные («actual metadata version is 2.4.0»). Официальный способ
// поднять версию — положить KGP нужной версии в classpath buildscript'а; тогда встроенная
// поддержка Kotlin работает уже на нём. Версии обязаны совпадать с gradle/libs.versions.toml.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}
