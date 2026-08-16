// Плагина org.jetbrains.kotlin.android здесь нет намеренно: с AGP 9 поддержка Kotlin встроена,
// а повторное применение падает на «extension with name 'kotlin' already registered».
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "ru.agimate.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.agimate.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

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
        }
        create("prod") {
            dimension = "backend"
            buildConfigField("String", "API_ORIGIN", "\"https://www.agimate.io\"")
            buildConfigField("boolean", "ALLOW_ORIGIN_OVERRIDE", "false")
            // Включить, когда на домене появится /.well-known/assetlinks.json с отпечатком
            // рабочей подписи. До этого App Link молча уводит редирект в браузер.
            buildConfigField("boolean", "USE_APP_LINK", "false")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
