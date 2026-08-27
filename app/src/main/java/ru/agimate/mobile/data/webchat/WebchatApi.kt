package ru.agimate.mobile.data.webchat

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.agimate.mobile.core.network.ApiEnvelope
import ru.agimate.mobile.core.network.PageEnvelope

/**
 * Переписки с агентами.
 *
 * Путей два семейства, и делятся они не по экранам, а по природе. Сама переписка — один ресурс
 * `/manage/sessions` независимо от того, чем она идёт: листинг, история, отметка прочтения и
 * закрытие общие для веб-чата, мессенджеров и IDE. В `/manage/webchat` остался только транспорт:
 * начать чат, отправить сообщение, взять токен на живой канал, список агентов как контактов.
 *
 * **Листинг сессий без `connectorCode` отдаёт все переписки пользователя**, а не только чаты: у
 * агента бывают сессии в мессенджерах и поток событий подключения вовсе без канала. Приложение
 * показывает только веб-чат, поэтому фильтр обязателен — см. [WebchatRepository.CONNECTOR_WEBCHAT].
 *
 * **Слэш в конце пути значим.** `GET .../sessions/` и `POST .../sessions` — разные маршруты, и
 * лишний или недостающий слэш даёт 404. Правило: путь, отдающий список, заканчивается слэшем.
 * Ниже пути выписаны точно — не «причёсывать».
 *
 * Загрузки здесь нет: файл — сущность control-api, а не вложение переписки, и грузится он через
 * [ru.agimate.mobile.data.files.FilesApi]. В сообщение уезжает только идентификатор.
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

    /** Фильтры необязательны и комбинируются; порядок — свежая активность сверху. */
    @GET("control/manage/sessions/")
    suspend fun sessions(
        @Query("agentId") agentId: String,
        @Query("connectorCode") connectorCode: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiEnvelope<PageEnvelope<WebchatSessionDto>>

    /** Одна сессия — строка того же вида, что в листинге. */
    @GET("control/manage/sessions/{id}")
    suspend fun session(@Path("id") sessionId: String): ApiEnvelope<WebchatSessionDto>

    /** Без слэша на конце — это создание, а не листинг. Ответ — строка вида листинга. */
    @POST("control/manage/webchat/sessions")
    suspend fun startSession(@Body body: StartSessionRequest): ApiEnvelope<WebchatSessionDto>

    /** Закрытие. Гасит бейдж: сессия помечается прочитанной целиком. */
    @POST("control/manage/sessions/{id}/close")
    suspend fun closeSession(@Path("id") sessionId: String): ApiEnvelope<WebchatSessionDto>

    /** Порядок — от новых к старым: первая страница это конец переписки, листание вверх — `page+1`. */
    @GET("control/manage/sessions/{id}/messages/")
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
    @POST("control/manage/sessions/{id}/read")
    suspend fun markRead(
        @Path("id") sessionId: String,
        @Body body: MarkReadRequest,
    ): ApiEnvelope<String?>

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
