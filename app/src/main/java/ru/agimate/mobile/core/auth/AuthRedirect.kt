package ru.agimate.mobile.core.auth

import android.net.Uri
import androidx.annotation.StringRes
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.R

/** Что приехало в адресе возврата из браузера. */
sealed interface AuthRedirect {
    /** Одноразовый код. Живёт 60 секунд и гасится первым обменом. */
    data class Code(val value: String) : AuthRedirect

    /**
     * Вместо кода пришёл `?error=…`. Приложение обязано это показать как «войти ещё раз», а не
     * зависнуть в ожидании.
     */
    data class Failed(val reason: String) : AuthRedirect

    companion object {
        /** Возвращает null, если ссылка не про авторизацию — обычный запуск по ярлыку. */
        fun parse(uri: Uri?): AuthRedirect? {
            if (uri == null) return null
            if (!matchesRedirect(uri)) return null
            uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { return Code(it) }
            uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let { return Failed(it) }
            return Failed("invalid_request")
        }

        private fun matchesRedirect(uri: Uri): Boolean {
            val scheme = uri.scheme ?: return false
            if (scheme == AuthConfig.SCHEME_REDIRECT_SCHEME) {
                return uri.host == AuthConfig.SCHEME_REDIRECT_HOST
            }
            return scheme == "https" &&
                "$scheme://${uri.host}${uri.path}" == AuthConfig.appLinkRedirect
        }
    }
}

object AuthConfig {
    const val SCHEME_REDIRECT_SCHEME = "agimate"
    const val SCHEME_REDIRECT_HOST = "auth"

    /** Фолбэк-адрес: схему может зарегистрировать чужое приложение, поэтому PKCE обязателен. */
    const val schemeRedirect = "$SCHEME_REDIRECT_SCHEME://$SCHEME_REDIRECT_HOST"

    /**
     * App Link. Проверяется доменом через `/.well-known/assetlinks.json`, присвоить его чужому
     * приложению нельзя — поэтому он предпочтительнее схемы.
     */
    val appLinkRedirect: String = BuildConfig.API_ORIGIN.trimEnd('/') + "/app/auth"

    /**
     * Адрес возврата, с которым идём на вход. Он же уходит в `redirectUri` при обмене: несовпадение
     * даёт 403 — это часть привязки кода, а не формальность.
     */
    val redirectUri: String
        get() = if (BuildConfig.USE_APP_LINK) appLinkRedirect else schemeRedirect
}

/**
 * Провайдеры входа. Отдельного эндпойнта «какие включены» у бэкенда нет — список зашит здесь.
 *
 * Название — ресурс, а не строка: «Яндекс» и «Yandex» это одна и та же компания, написанная на
 * двух языках, и выбирать между ними должна локаль, а не константа.
 */
enum class AuthProvider(val code: String, @param:StringRes val titleRes: Int) {
    GOOGLE("google", R.string.provider_google),
    YANDEX("yandex", R.string.provider_yandex),
    VK("vk", R.string.provider_vk),
    GITHUB("github", R.string.provider_github),
}
