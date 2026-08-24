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
     * Доказательство, что круг к провайдеру прошёл, — но не вход: аккаунт назовёт следующий запрос,
     * заголовком `Authorization`. Живёт 5 минут и тратится один раз.
     *
     * @param provider провайдер, с которым круг прошёл; сервер называет его строчными.
     */
    data class LinkProof(val value: String, val provider: AuthProvider?) : AuthRedirect

    /**
     * Вместо кода пришёл `?error=…`. Приложение обязано это показать как «войти ещё раз», а не
     * зависнуть в ожидании.
     */
    data class Failed(val reason: String) : AuthRedirect

    companion object {
        /**
         * Возвращает null, если ссылка не про авторизацию — обычный запуск по ярлыку.
         *
         * Вход и привязка возвращаются в один и тот же адрес, и различать их приходится по
         * параметру: `code` — это вход, `link_proof` — это привязка. Отдельного адреса под
         * привязку нет намеренно: их пришлось бы держать в белом списке сервера обоими.
         */
        fun parse(uri: Uri?): AuthRedirect? {
            if (uri == null) return null
            if (!matchesRedirect(uri)) return null
            uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { return Code(it) }
            uri.getQueryParameter("link_proof")?.takeIf { it.isNotBlank() }?.let {
                return LinkProof(it, AuthProvider.of(uri.getQueryParameter("provider")))
            }
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
 *
 * Регистр имени значим в путях API: [code] — идентификатор регистрации у самого провайдера
 * (строчными, им начинается круг), [name] — значение перечисления (заглавными, им провайдера
 * отвязывают).
 *
 * @param offered предлагаем ли этого провайдера как новую дверь. Невыключенный провайдер из
 *                перечисления не исчезает: у кого он уже привязан, тот должен видеть его в списке
 *                способов входа под нормальным названием и уметь отвязать.
 */
enum class AuthProvider(
    val code: String,
    @param:StringRes val titleRes: Int,
    val offered: Boolean = true,
) {
    GOOGLE("google", R.string.provider_google),
    YANDEX("yandex", R.string.provider_yandex),
    VK("vk", R.string.provider_vk, offered = false),
    GITHUB("github", R.string.provider_github);

    companion object {
        /** Что показывать кнопками — и на входе, и в привязке. */
        val offered: List<AuthProvider> get() = entries.filter { it.offered }

        /** Понимает оба написания: `github` из редиректа и `GITHUB` из тела ответа. */
        fun of(raw: String?): AuthProvider? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            return entries.firstOrNull { it.code.equals(value, ignoreCase = true) }
        }
    }
}
