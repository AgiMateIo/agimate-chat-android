package ru.agimate.mobile.core.auth

import android.net.Uri
import android.os.Build
import ru.agimate.mobile.core.network.OriginProvider
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.unwrap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вход: authorization code flow с PKCE, где сервером авторизации выступает наш же бэкенд.
 *
 * Веб держит refresh в httpOnly-cookie; приложению так нельзя — у внешнего браузера свой cookie jar,
 * до которого HTTP-клиент приложения не дотягивается. Поэтому вход заканчивается одноразовым кодом
 * в редиректе, который приложение меняет на пару токенов в теле ответа.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val store: TokenStore,
    private val origins: OriginProvider,
) {
    /**
     * Verifier живёт в памяти процесса на время входа: он существует минуты и переживать вход не
     * должен. Плата — если систему прижмёт по памяти и процесс убьют, пока открыт браузер, вход
     * придётся начать заново; [PendingLoginLost] описывает ровно этот случай.
     */
    private val pendingVerifier = AtomicReference<String?>(null)

    /** Адрес, который открываем в Custom Tabs. */
    fun authorizationUri(provider: AuthProvider, referralCode: String? = null): Uri {
        val verifier = Pkce.createVerifier()
        pendingVerifier.set(verifier)

        return Uri.parse(origins.userBase)
            .buildUpon()
            .appendPath("oauth2")
            .appendPath("authorization")
            .appendPath(provider.code)
            .appendQueryParameter("redirect_to", AuthConfig.redirectUri)
            .appendQueryParameter("code_challenge", Pkce.challengeOf(verifier))
            .apply {
                referralCode?.takeIf { it.isNotBlank() }
                    ?.let { appendQueryParameter("ref", it) }
            }
            .build()
    }

    fun abandonPendingLogin() {
        pendingVerifier.set(null)
    }

    /**
     * Обмен кода на токены. Verifier забирается ровно один раз: повторный обмен тем же кодом не
     * просто отказ — он **отзывает сессию**, которую выдал первый обмен. Поэтому запрос не
     * повторяется вслепую при сетевой ошибке: если ответ не дошёл, безопаснее начать вход заново.
     */
    suspend fun exchangeCode(code: String): Result<Unit> {
        val verifier = pendingVerifier.getAndSet(null)
            ?: return Result.failure(PendingLoginLost())

        return runCatching {
            val response = apiCall {
                api.exchangeCode(
                    NativeTokenRequest(
                        code = code,
                        codeVerifier = verifier,
                        // Тот же адрес, что был в redirect_to при старте: несовпадение даёт 403.
                        redirectUri = AuthConfig.redirectUri,
                        deviceName = deviceName(),
                    )
                )
            }.unwrap("обмен кода на токены")

            store.save(
                AuthTokens(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    sessionId = response.sessionId,
                )
            )
        }
    }

    /**
     * Логаут отзывает строку сессии на сервере — токен перестаёт работать везде, а не только на
     * этом устройстве. Локальную очистку делаем в любом случае: если сеть подвела, держать у себя
     * токены незачем.
     */
    suspend fun logout() {
        val tokens = store.load()
        if (tokens != null) {
            runCatching { api.logout(LogoutRequest(tokens.refreshToken)) }
        }
        store.clear()
    }

    /**
     * Как устройство будет называться в списке «мои устройства». Без него в списке окажется строка
     * User-Agent, что человеку ни о чём не говорит.
     */
    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = when {
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isBlank() -> model
            else -> "$manufacturer $model"
        }.trim()
        return name.ifBlank { "Android" }
            .replaceFirstChar { it.uppercase() }
    }
}

/**
 * Код приехал, а verifier'а нет: процесс перезапустили, пока был открыт браузер.
 *
 * Текста для человека здесь нет намеренно — его подбирает экран входа: этот класс про то, что
 * случилось, а не про то, как об этом сказать.
 */
class PendingLoginLost : Exception("pending login lost")
