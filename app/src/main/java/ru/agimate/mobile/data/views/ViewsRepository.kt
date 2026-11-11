package ru.agimate.mobile.data.views

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.core.views.ViewExternalDomains
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

data class AgentViewContent(
    val html: String,
    val mimeType: String,
    /** Домены, разрешённые установкой; `null` — строгая политика без внешней сети. */
    val externalDomains: ViewExternalDomains? = null,
)

/**
 * Вызов тула в строке переписки. [viewUri] — подсказка для карточки: `null` значит «вью нет»,
 * а не «ещё не знаем». [connectionId] может отсутствовать у вызова без коннекции.
 */
data class ToolCallDescriptor(
    val toolCallId: String,
    val name: String,
    val connectionId: String?,
    val viewUri: String?,
) {
    companion object {
        fun from(dto: ToolCallDescriptorDto) = ToolCallDescriptor(
            toolCallId = dto.toolCallId,
            name = dto.name,
            connectionId = dto.connectionId,
            viewUri = dto.viewUri,
        )
    }
}

/**
 * Данные вызова для вью. [input] всегда объект: пустые аргументы — тоже аргументы. [result] —
 * `CallToolResult` как JSON, `null` пока вызов идёт.
 */
data class SessionToolCall(
    val toolCallId: String,
    val name: String,
    val connectionId: String?,
    val viewUri: String?,
    val input: JsonObject,
    val result: JsonObject?,
) {
    companion object {
        fun from(dto: SessionToolCallDto) = SessionToolCall(
            toolCallId = dto.toolCallId,
            name = dto.name,
            connectionId = dto.connectionId,
            viewUri = dto.viewUri,
            input = dto.input ?: JsonObject(emptyMap()),
            result = dto.result?.toJson(),
        )
    }
}

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
        return AgentViewContent(
            html = dto.html,
            mimeType = dto.mimeType,
            externalDomains = dto.csp.toDomains(),
        )
    }

    /**
     * Разрешить вызов в его вью. `404`/`403` — нормальный ответ «вью нет», а не поломка: их
     * разбирает загрузчик, а не репозиторий.
     */
    suspend fun toolCall(sessionId: String, toolCallId: String): SessionToolCall =
        apiCall { api.toolCall(sessionId, toolCallId) }
            .unwrap("вызов тула")
            .let(SessionToolCall::from)

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
        return dto.toJson()
    }
}

/**
 * `CallToolResult` без обёрток. Ключ `isError` есть всегда: страница отличает ошибку тула от
 * успешного пустого результата, а `content`/`structuredContent` не появляются, если сервер их не
 * прислал.
 */
private fun ViewToolCallResultDto.toJson(): JsonObject = buildJsonObject {
    content?.let { put("content", it) }
    structuredContent?.let { put("structuredContent", it) }
    put("isError", isError)
}

/** Домены из ответа; пустая пара списков — тот же строгий случай, что и отсутствие поля. */
private fun AgentViewCspDto?.toDomains(): ViewExternalDomains? {
    if (this == null) return null
    val domains = ViewExternalDomains(
        resourceDomains = resourceDomains.orEmpty(),
        connectDomains = connectDomains.orEmpty(),
    )
    return domains.takeIf {
        it.resourceDomains.isNotEmpty() || it.connectDomains.isNotEmpty()
    }
}
