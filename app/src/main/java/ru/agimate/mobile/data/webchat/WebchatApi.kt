package ru.agimate.mobile.data.webchat

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import ru.agimate.mobile.core.network.ApiEnvelope
import ru.agimate.mobile.core.network.PageEnvelope

/**
 * Переписки с агентами.
 *
 * **Слэш в конце пути значим.** `GET .../sessions/` и `POST .../sessions` — разные маршруты, и
 * лишний или недостающий слэш даёт 404. Правило: путь, отдающий список, заканчивается слэшем.
 * Ниже пути выписаны точно — не «причёсывать».
 */
interface WebchatApi {

    /**
     * Список агентов как контактов — один запрос на экран.
     *
     * Порядок серверный: по свежести переписки, агенты без чатов в хвосте. Пересортировывать на
     * клиенте нельзя — между страницами такой порядок не восстановить.
     */
    @GET("control/manage/webchat/contacts/")
    suspend fun contacts(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiEnvelope<PageEnvelope<WebchatContactDto>>

    @GET("control/manage/webchat/sessions/")
    suspend fun sessions(
        @Query("agentId") agentId: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiEnvelope<PageEnvelope<WebchatSessionDto>>

    /** Без слэша на конце — это создание, а не листинг. */
    @POST("control/manage/webchat/sessions")
    suspend fun startSession(@Body body: StartSessionRequest): ApiEnvelope<WebchatSessionDto>

    /** Закрытие. Гасит бейдж: сессия помечается прочитанной целиком. */
    @DELETE("control/manage/webchat/sessions/{id}")
    suspend fun closeSession(@Path("id") sessionId: String): ApiEnvelope<WebchatSessionDto>

    /** Порядок — от новых к старым: первая страница это конец переписки, листание вверх — `page+1`. */
    @GET("control/manage/webchat/sessions/{id}/messages/")
    suspend fun messages(
        @Path("id") sessionId: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiEnvelope<PageEnvelope<WebchatMessageDto>>

    /**
     * Отправка синхронная: маршрутизация происходит внутри запроса, поэтому ошибка приходит сразу,
     * а не «принято, а потом упало».
     */
    @POST("control/manage/webchat/sessions/{id}/messages")
    suspend fun sendMessage(
        @Path("id") sessionId: String,
        @Body body: SendMessageRequest,
    ): ApiEnvelope<SendMessageResponseDto>

    /** Передаётся `id` строки, а не `messageId` — иначе 400. */
    @POST("control/manage/webchat/sessions/{id}/read")
    suspend fun markRead(
        @Path("id") sessionId: String,
        @Body body: MarkReadRequest,
    ): ApiEnvelope<String?>

    @Multipart
    @POST("control/manage/webchat/files")
    suspend fun uploadFile(@Part file: MultipartBody.Part): ApiEnvelope<WebchatFileDto>

    /** Токены на канал одной переписки: `webchat:{sessionId}`. */
    @POST("control/manage/webchat/sessions/{id}/token")
    suspend fun sessionToken(@Path("id") sessionId: String): ApiEnvelope<CentrifugoTokenDto>

    /** Токены на личный канал пользователя: `user:{userId}`. Одна подписка на всё приложение. */
    @POST("control/manage/centrifugo/token")
    suspend fun userToken(): ApiEnvelope<CentrifugoTokenDto>

    /**
     * Отмена по сессии, а не по запуску: отмена одного запуска позволит стартовать следующему из
     * очереди этой же сессии. Идемпотентна.
     */
    @POST("control/manage/runs/sessions/{sessionId}/cancel")
    suspend fun cancelSession(@Path("sessionId") sessionId: String): ApiEnvelope<CancelSessionDto>
}
