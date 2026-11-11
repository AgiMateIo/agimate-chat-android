package ru.agimate.mobile.data.views

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.agimate.mobile.core.network.ApiEnvelope

/**
 * Эндпойнты вью агента. Слэш на конце есть только у списка — без него сервер отвечает `404`.
 */
interface ViewsApi {

    /** Список собирается бэкендом из каталога тулов агента: привязок и ABAC, — сама модель его не видит. */
    @GET("control/manage/agents/{agentId}/views/")
    suspend fun views(@Path("agentId") agentId: String): ApiEnvelope<List<AgentViewDto>>

    /** [uri] — `ui://…`; кодируется как обычный query-параметр. */
    @GET("control/manage/agents/{agentId}/views/content")
    suspend fun content(
        @Path("agentId") agentId: String,
        @Query("connectionId") connectionId: String,
        @Query("uri") uri: String,
    ): ApiEnvelope<AgentViewContentDto>

    /** Вызов исполняется от имени агента — с ABAC и видимостью тула. */
    @POST("control/manage/agents/{agentId}/views/tools/call")
    suspend fun callTool(
        @Path("agentId") agentId: String,
        @Body body: ViewToolCallRequest,
    ): ApiEnvelope<ViewToolCallResultDto>

    /**
     * Разрешение вызова в его вью. Путь принадлежит сессии, но потребитель — вью: страница, вход и
     * результат нужны только ей. Держим здесь, чтобы `ViewsRepository` не зависел от `WebchatApi`.
     */
    @GET("control/manage/sessions/{sessionId}/tool-calls/{toolCallId}")
    suspend fun toolCall(
        @Path("sessionId") sessionId: String,
        @Path("toolCallId") toolCallId: String,
    ): ApiEnvelope<SessionToolCallDto>
}
