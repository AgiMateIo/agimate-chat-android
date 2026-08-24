package ru.agimate.mobile.core.auth

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import ru.agimate.mobile.core.network.ApiEnvelope

/** Приложение всегда представляется так: от этого зависит, где приедет refresh — в теле или в cookie. */
private const val NATIVE = "NATIVE"

@Serializable
data class NativeTokenRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val deviceName: String?,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PasswordLoginRequest(
    val email: String,
    val password: String,
    val deviceName: String?,
    /**
     * `@EncodeDefault` здесь обязателен, и это не украшение: общий `Json` собран с
     * `encodeDefaults = false`, поле со значением по умолчанию в тело не попало бы вовсе, а
     * молчание сервер читает как `WEB`. Тогда refresh уехал бы в cookie, которой у приложения нет,
     * и вход закончился бы парой без второй половины.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val client: String = NATIVE,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val displayName: String? = null,
    val ref: String? = null,
)

/** Одна форма на два письма: повторное подтверждение и ссылка на пароль. */
@Serializable
data class EmailRequest(val email: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshTokenId: String? = null,
    val refreshToken: String,
    /**
     * Секунды жизни access-токена. Срок берётся отсюда, а не из константы: он меняется на сервере
     * без предупреждения клиентов — в августе 2026 уже уехал с суток на час.
     */
    val expiresIn: Int = 0,
    val sessionId: String,
)

/**
 * Всё, что выдаёт токены, плюс письма, которые ничего не выдают. Ходит отдельным HTTP-клиентом —
 * без `Authorization` и без `Authenticator`: иначе 401 на обновлении утащил бы обновление в
 * рекурсию, а вход по паролю получил бы к своему 401 бессмысленный круг обновления.
 */
interface AuthApi {

    /**
     * Код живёт 60 секунд и гасится первым обменом. Второй обмен тем же кодом не просто отказ — он
     * **отзывает сессию**, которую выдал первый. Поэтому вызов не повторяется вслепую при сетевой
     * ошибке: если ответ не дошёл, безопаснее начать вход заново.
     */
    @POST("user/oauth2/native/token")
    suspend fun exchangeCode(@Body body: NativeTokenRequest): ApiEnvelope<AuthResponseDto>

    /**
     * Вход по паролю. Неизвестный адрес и неверный пароль дают одинаковый 401 — и одинаковый по
     * времени ответа; различать их на экране нечем и не нужно. 429 — десять неудач по одному ящику
     * за четверть часа, срок повтора сервер называет в тексте.
     */
    @POST("user/auth/login")
    suspend fun login(@Body body: PasswordLoginRequest): ApiEnvelope<AuthResponseDto>

    /**
     * Заявка на регистрацию. Пароля здесь нет: его называет тот, кто откроет письмо, — иначе
     * посторонний заводил бы чужой адрес с известным ему паролем.
     *
     * Ответ одинаков всегда: адрес свободен, занят или ему уже слали пять писем за час. Это и есть
     * проверялка «кто здесь зарегистрирован», и отвечать на неё по существу нельзя.
     */
    @POST("user/auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiEnvelope<String>

    @POST("user/auth/register/resend")
    suspend fun resendConfirmation(@Body body: EmailRequest): ApiEnvelope<String>

    /** Она же «добавить пароль»: письмо одно и то же, отличается только точка входа на экране. */
    @POST("user/auth/password/forgot")
    suspend fun forgotPassword(@Body body: EmailRequest): ApiEnvelope<String>

    /** Ответ нужен со статусом: 409 и 403 значат разное и обрабатываются по-разному. */
    @POST("user/oauth2/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<ApiEnvelope<AuthResponseDto>>

    /** Отзывает строку сессии на сервере — токен перестаёт работать везде, а не только здесь. */
    @POST("user/oauth2/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<ApiEnvelope<String>>
}
