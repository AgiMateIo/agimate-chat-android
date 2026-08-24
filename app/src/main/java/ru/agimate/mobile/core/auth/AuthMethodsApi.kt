package ru.agimate.mobile.core.auth

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import ru.agimate.mobile.core.network.ApiEnvelope
import ru.agimate.mobile.core.network.InstantSerializer
import java.time.Instant

/**
 * Одна запись экрана «способы входа»: либо провайдер, либо пароль.
 *
 * @param provider у пароля `null` — это и отличает запись пароля от записи провайдера.
 * @param title название способа от сервера. Показывается только тогда, когда провайдер приложению
 *              незнаком: у известных название берётся из ресурсов, потому что «Яндекс» и «Yandex» —
 *              это одна компания на двух языках.
 * @param email  адрес, который называет провайдер. Бывает `null`: привязанный вручную провайдер
 *               вправе адреса не сообщать, и это не ошибка.
 * @param addedAt когда способ появился; у пароля — когда его последний раз задавали.
 */
@Serializable
data class LoginMethodDto(
    val kind: String? = null,
    val provider: String? = null,
    val title: String? = null,
    val email: String? = null,
    @Serializable(with = InstantSerializer::class)
    val addedAt: Instant? = null,
)

@Serializable
data class LinkProofRequest(val proof: String)

/** @param outcome `LINKED`, `ALREADY_YOURS`, `TAKEN` или `PROVIDER_OCCUPIED`. */
@Serializable
data class LinkProviderDto(
    val provider: String? = null,
    val outcome: String? = null,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/**
 * Способы попасть в аккаунт: перечислить, добавить, убрать. Ходит с `Authorization` — в отличие от
 * [AuthApi], который токенов ещё не имеет либо получает их сам.
 *
 * Регистр провайдера в путях разный, и это не опечатка: в круг к провайдеру он едет строчными
 * (идентификатор регистрации у самого провайдера), а в отвязке — заглавными (значение
 * перечисления). См. [AuthProvider.code] против [AuthProvider.name].
 */
interface AuthMethodsApi {

    /** Слэш на конце значим: без него 404. */
    @GET("user/auth/methods/")
    suspend fun methods(): ApiEnvelope<List<LoginMethodDto>>

    /**
     * Второй шаг привязки: доказательство из редиректа меняется на связь с **этим** аккаунтом.
     *
     * Аккаунт называет заголовок `Authorization`, а не доказательство, — в этом весь смысл двух
     * шагов: перейти по адресу браузер может заставить чужая страница, а послать заголовок — нет.
     *
     * Отказы по существу (`TAKEN`, `PROVIDER_OCCUPIED`) приходят с кодом 200: с запросом всё в
     * порядке, различать их надо по `outcome`. 403 — доказательство просрочено, потрачено или
     * подделано.
     */
    @POST("user/auth/methods/link")
    suspend fun link(@Body body: LinkProofRequest): ApiEnvelope<LinkProviderDto>

    /** Провайдер здесь **заглавными**: это значение перечисления, а не имя регистрации. */
    @DELETE("user/auth/methods/oauth/{provider}")
    suspend fun unlinkProvider(@Path("provider") provider: String): ApiEnvelope<String>

    @DELETE("user/auth/methods/password")
    suspend fun removePassword(): ApiEnvelope<String>

    /**
     * Смена пароля гасит все **остальные** сессии, текущая переживает: знание текущего пароля не
     * повод считать аккаунт захваченным и заново входить на всех устройствах.
     */
    @POST("user/auth/password/change")
    suspend fun changePassword(@Body body: ChangePasswordRequest): ApiEnvelope<String>
}
