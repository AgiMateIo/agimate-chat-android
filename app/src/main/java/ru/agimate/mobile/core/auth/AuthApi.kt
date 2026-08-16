package ru.agimate.mobile.core.auth

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import ru.agimate.mobile.core.network.ApiEnvelope

@Serializable
data class NativeTokenRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val deviceName: String?,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshTokenId: String? = null,
    val refreshToken: String,
    val expiresIn: Int = 0,
    val sessionId: String,
)

/**
 * Обмен кода, обновление и логаут. Ходит отдельным HTTP-клиентом — без `Authorization` и без
 * `Authenticator`: иначе 401 на обновлении утащил бы обновление в рекурсию.
 */
interface AuthApi {

    /**
     * Код живёт 60 секунд и гасится первым обменом. Второй обмен тем же кодом не просто отказ — он
     * **отзывает сессию**, которую выдал первый. Поэтому вызов не повторяется вслепую при сетевой
     * ошибке: если ответ не дошёл, безопаснее начать вход заново.
     */
    @POST("user/oauth2/native/token")
    suspend fun exchangeCode(@Body body: NativeTokenRequest): ApiEnvelope<AuthResponseDto>

    /** Ответ нужен со статусом: 409 и 403 значат разное и обрабатываются по-разному. */
    @POST("user/oauth2/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<ApiEnvelope<AuthResponseDto>>

    /** Отзывает строку сессии на сервере — токен перестаёт работать везде, а не только здесь. */
    @POST("user/oauth2/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<ApiEnvelope<String>>
}
