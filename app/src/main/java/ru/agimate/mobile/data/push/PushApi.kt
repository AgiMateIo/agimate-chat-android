package ru.agimate.mobile.data.push

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.PUT
import ru.agimate.mobile.core.network.ApiEnvelope

/**
 * @param provider как транспорт называет себя сам: `rustore`, `firebase` или `hms`. Каналов у
 *   устройства может быть несколько, и сервер должен знать, куда отправлять.
 */
@Serializable
data class PushSubscriptionRequest(
    val provider: String,
    val token: String,
)

@Serializable
data class PushUnsubscribeRequest(
    val token: String,
)

/**
 * Подписка на пуши принадлежит **входу**, а не пользователю: сессию сервер берёт из access-токена
 * (claim `asid`), поэтому передавать её не нужно. Отсюда же главное следствие — после каждого входа
 * подписку надо регистрировать заново: со старой сессией её уже удалили.
 */
interface PushApi {

    /** Идемпотентно: повторная регистрация того же токена — продление, а не второе устройство. */
    @PUT("user/push/subscriptions")
    suspend fun subscribe(@Body body: PushSubscriptionRequest): ApiEnvelope<String>

    /**
     * Токен едет телом, а не в query: query оседает в логах прокси, а токен — это право слать
     * уведомления на устройство.
     */
    @HTTP(method = "DELETE", path = "user/push/subscriptions", hasBody = true)
    suspend fun unsubscribe(@Body body: PushUnsubscribeRequest): ApiEnvelope<String>
}
