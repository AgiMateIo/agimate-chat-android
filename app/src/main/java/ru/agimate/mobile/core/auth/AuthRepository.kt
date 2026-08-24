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
 * Три пути внутрь: провайдер через браузер, пароль и письмо, заводящее пароль.
 *
 * Провайдерский путь — authorization code flow с PKCE, где сервером авторизации выступает наш же
 * бэкенд. Веб держит refresh в httpOnly-cookie; приложению так нельзя — у внешнего браузера свой
 * cookie jar, до которого HTTP-клиент приложения не дотягивается. Поэтому вход заканчивается
 * одноразовым кодом в редиректе, который приложение меняет на пару токенов в теле ответа.
 *
 * Путь по паролю проще ровно потому, что браузера в нём нет: одна форма, один запрос, та же пара
 * токенов в ответе.
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

        return authorizationUri(provider) {
            appendQueryParameter("code_challenge", Pkce.challengeOf(verifier))
            referralCode?.takeIf { it.isNotBlank() }
                ?.let { appendQueryParameter("ref", it) }
        }
    }

    /**
     * Тот же круг, прочитанный как привязка: `link=1` — ровно эта строка, иначе сервер поймёт его
     * как обычный вход и заведёт второй аккаунт вместо второй двери в этот.
     *
     * PKCE здесь не нужен и не бывает: круг не выдаёт ни кода, ни токенов — только доказательство,
     * которое без `Authorization` владельца не обменивается ни на что.
     */
    fun linkingUri(provider: AuthProvider): Uri = authorizationUri(provider) {
        appendQueryParameter("link", "1")
    }

    private fun authorizationUri(
        provider: AuthProvider,
        extras: Uri.Builder.() -> Unit,
    ): Uri = Uri.parse(origins.userBase)
        .buildUpon()
        .appendPath("oauth2")
        .appendPath("authorization")
        .appendPath(provider.code)
        .appendQueryParameter("redirect_to", AuthConfig.redirectUri)
        .apply(extras)
        .build()

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

            keep(response)
        }
    }

    /**
     * Вход по паролю. Неизвестный адрес и неверный пароль приходят одинаковым 401 — различать их
     * на экране нечем, и пробовать не надо: разный текст на два случая и был бы той самой
     * проверялкой, кто здесь зарегистрирован.
     */
    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        val response = apiCall {
            api.login(
                PasswordLoginRequest(
                    email = email.trim(),
                    password = password,
                    deviceName = deviceName(),
                )
            )
        }.unwrap("вход по паролю")

        keep(response)
    }

    /**
     * Заявка на регистрацию: аккаунта после неё ещё нет, есть письмо. Пароль назовёт тот, кто
     * письмо откроет, — и потому вход в приложение продолжится уже обычной формой входа.
     */
    suspend fun register(email: String, displayName: String?): Result<Unit> = runCatching {
        apiCall {
            api.register(
                RegisterRequest(
                    email = email.trim(),
                    displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
                )
            )
        }
        Unit
    }

    suspend fun resendConfirmation(email: String): Result<Unit> = runCatching {
        apiCall { api.resendConfirmation(EmailRequest(email.trim())) }
        Unit
    }

    /**
     * Письмо со ссылкой на пароль. Одна операция на два случая: «забыли пароль» на входе и
     * «добавить пароль» в способах входа шлют ровно это.
     */
    suspend fun requestPasswordLetter(email: String): Result<Unit> = runCatching {
        apiCall { api.forgotPassword(EmailRequest(email.trim())) }
        Unit
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

    /** Момент обновления считается от `expiresIn` ответа, а не от константы в коде. */
    private fun keep(response: AuthResponseDto) {
        store.save(response.toTokens(System.currentTimeMillis()))
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
