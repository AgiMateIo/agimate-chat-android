package ru.agimate.mobile.data.views

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.unwrap
import javax.inject.Inject
import javax.inject.Singleton

/** Вью агента глазами списка: чья привязка, какая страница и какие тулы она умеет вызывать. */
data class AgentView(
    val connectionId: String,
    val connectorCode: String,
    val connectionName: String?,
    val uri: String,
    val tools: List<String>,
) {
    /** Имя привязки; у безымянной — код коннектора: по нему её и узнают. */
    val title: String get() = connectionName?.takeIf { it.isNotBlank() } ?: connectorCode
}

data class AgentViewContent(val html: String, val mimeType: String)

@Singleton
class ViewsRepository @Inject constructor(private val api: ViewsApi) {

    suspend fun list(agentId: String): List<AgentView> =
        apiCall { api.views(agentId) }.unwrap("список вью агента").map {
            AgentView(
                connectionId = it.connectionId,
                connectorCode = it.connectorCode,
                connectionName = it.connectionName,
                uri = it.uri,
                tools = it.tools,
            )
        }

    suspend fun content(agentId: String, connectionId: String, uri: String): AgentViewContent {
        val dto = apiCall { api.content(agentId, connectionId, uri) }.unwrap("содержимое вью")
        return AgentViewContent(html = dto.html, mimeType = dto.mimeType)
    }

    /**
     * Вызов тула из открытой вью. Возвращает ровно JSON-RPC `result`: страница ждёт его полем
     * `result` ответа, а не наш конверт. Ошибка тула (`isError`) — тоже результат, поэтому ключ
     * есть всегда; `content` и `structuredContent` — только когда сервер их прислал.
     */
    suspend fun callTool(
        agentId: String,
        connectionId: String,
        name: String,
        arguments: JsonObject,
    ): JsonObject {
        val dto = apiCall {
            api.callTool(agentId, ViewToolCallRequest(connectionId, name, arguments))
        }.unwrap("вызов тула вью")
        return buildJsonObject {
            dto.content?.let { put("content", it) }
            dto.structuredContent?.let { put("structuredContent", it) }
            put("isError", dto.isError)
        }
    }
}
