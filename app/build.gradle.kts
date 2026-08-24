import java.util.Properties

// Плагина org.jetbrains.kotlin.android здесь нет намеренно: с AGP 9 поддержка Kotlin встроена,
// а повторное применение падает на «extension with name 'kotlin' already registered».
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Требует app/google-services.json: без файла сборка падает осознанно, а не выдаёт
    // приложение, молча оставшееся без второго канала уведомлений.
    alias(libs.plugins.google.services)
}

/**
 * Ключ подписи в репозитории не лежит и лежать не будет: он задаёт личность приложения навсегда, и
 * магазин запоминает сертификат первой загрузки. Путь и пароли — в local.properties, которого в git
 * нет.
 *
 * Если ключа нет, релиз собирается неподписанным, а не падает: у форка и у чужой машины своего
 * ключа быть не может, и ломать им сборку из-за этого незачем — подписать APK можно и потом,
 * apksigner'ом.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val releaseKeystore = localProperties.getProperty("release.storeFile")
    ?.let(::file)
    ?.takeIf { it.exists() }

/**
 * Версия — единственное место, где её задают руками. Номер сборки из неё выводится: держать два
 * числа рядом значит однажды поднять одно и забыть второе, а магазин ловит это уже после загрузки.
 *
 * Формула — по три разряда на каждое поле semver: 0.3.1 → 3001, 1.0.0 → 1000000. Разрядность
 * выбрана так, чтобы номер читался глазами: 3001 разбирается обратно в 0.3.1 без калькулятора.
 * Потолок поля — 999, до потолка `versionCode` (2 100 000 000) отсюда ещё два порядка.
 *
 * Переход на формулу поднял номер сразу с 3 до тысяч. Это безопасно: магазины требуют, чтобы
 * номер рос, а не чтобы рос на единицу.
 */
val appVersionName = "0.3.3"

val appVersionCode = appVersionName.split(".").map { it.toIntOrNull() ?: -1 }.let { parts ->
    require(parts.size == 3 && parts.all { it in 0..999 }) {
        "versionName «$appVersionName» — не semver из трёх полей до 999, номер сборки не вывести"
    }
    parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
}

android {
    namespace = "ru.agimate.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.agimate.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Схема — фолбэк-адрес возврата из Custom Tabs. Значение должно совпадать с тем, что
        // сервер держит в native-redirect-urls, посимвольно: сверка там точным равенством.
        manifestPlaceholders["authScheme"] = "agimate"
        manifestPlaceholders["authHost"] = "auth"
    }

    // Один applicationId на оба флейвора намеренно: две установленные копии заявили бы одну и ту же
    // схему agimate://auth, и возврат из браузера упирался бы в диалог выбора приложения.
    flavorDimensions += "backend"
    productFlavors {
        create("dev") {
            dimension = "backend"
            // Хост машины со стороны эмулятора. Caddy в ops/ имеет фолбэк-блок на любой Host,
            // так что /user, /control и /connection/websocket доезжают.
            buildConfigField("String", "API_ORIGIN", "\"http://10.0.2.2:8000\"")
            buildConfigField("boolean", "ALLOW_ORIGIN_OVERRIDE", "true")
            buildConfigField("boolean", "USE_APP_LINK", "false")
            // Проект пуш-уведомлений из консоли RuStore. Пустой — пуши просто не поднимаются:
            // приложение работает как раньше, живая лента никуда не девается.
            buildConfigField("String", "RUSTORE_PROJECT_ID", "\"-eeO-sKPI0qq82bCMFqbndv2iR8kNPrd\"")
        }
        create("prod") {
            dimension = "backend"
            // Именно api.*, а не www: на www.agimate.io стоит сайт, и он уводит запрос на
            // локализованный путь (307 на /ru/...), где API нет — приложение получало 404 на
            // профиле и показывало «не найдено». OAuth на бэкенде тоже настроен на этот хост:
            // redirect_uri в ответе /user/oauth2/authorization/* указывает на api.agimate.io.
            buildConfigField("String", "API_ORIGIN", "\"https://api.agimate.io\"")
            buildConfigField("boolean", "ALLOW_ORIGIN_OVERRIDE", "false")
            // Пока тот же проект, что у стенда: в проекте пушей один отпечаток подписи, и там
            // сейчас отладочный — то есть боевая сборка токена по нему не получит. Как появится
            // релизный ключ, здесь должен встать свой проект с его отпечатком.
            buildConfigField("String", "RUSTORE_PROJECT_ID", "\"-eeO-sKPI0qq82bCMFqbndv2iR8kNPrd\"")
            // Включить, когда на домене появится /.well-known/assetlinks.json с отпечатком
            // рабочей подписи. До этого App Link молча уводит редирект в браузер.
            buildConfigField("boolean", "USE_APP_LINK", "false")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // findByName, а не getByName: без ключа конфигурации нет вовсе, и это не ошибка.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // jvmTarget встроенный Kotlin берёт отсюда сам — отдельно его задавать не нужно.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Собирает localeConfig из имеющихся values-*: без него в настройках Android 13+ у
        // приложения нет своего выбора языка, и локаль можно поменять только для всей системы.
        // Язык базовых ресурсов задаётся файлом res/resources.properties.
        generateLocaleConfig = true

        // Приложение говорит на двух языках, а AndroidX приносит с собой десятки. Без этого списка
        // немецкий телефон получил бы интерфейс, где системные кнопки по-немецки, а всё наше —
        // по-английски: языковая каша выглядит хуже честного одного языка. Заодно APK худеет.
        localeFilters += listOf("en", "ru")
    }

    testOptions {
        // Юнит-тесты видят заглушку android.jar, где `Log` бросает «not mocked». Отладочные строки
        // есть почти в каждом классе, и проверяем мы не их — пусть заглушки молча возвращают ноль.
        unitTests.isReturnDefaultValues = true
    }

    bundle {
        language {
            // Смена языка в профиле несовместима с разделением бандла по языкам: split оставил бы
            // на устройстве ресурсы только системного языка, и переключатель показывал бы список,
            // в котором выбрать нечего.
            enableSplit = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.browser)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)

    implementation(libs.centrifuge)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    implementation(libs.rustore.universalpush)
    implementation(libs.rustore.universalrustore)
    implementation(libs.rustore.universalfcm)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
