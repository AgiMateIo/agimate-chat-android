package ru.agimate.mobile.data.views

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Вью коннектора в списке агента. `uri` — идентификатор MCP-ресурса `ui://…`, а не сетевой адрес:
 * страница приходит содержимым по HTTP, открывать её по `uri` некуда.
 */
@Serializable
data class AgentViewDto(
    val connectionId: String,
    val connectorCode: String,
    val connectionName: String? = null,
    val uri: String,
    val tools: List<String> = emptyList(),
)

/**
 * Страница вью. `permissions` и `prefersBorder` сервер отдаёт из `_meta.ui`, но приложению они не
 * нужны: политику безопасности задаёт обёртка, рамку рисует экран.
 */
@Serializable
data class AgentViewContentDto(
    val uri: String,
    val mimeType: String,
    val html: String,
    val permissions: JsonObject? = null,
    val prefersBorder: Boolean? = null,
)

/**
 * Тело `tools/call`. У [arguments] нет дефолта намеренно: пустой объект — тоже аргументы, и
 * уехать он должен всегда. [connectionId] берётся из нативного состояния открытой вью, а не из
 * сообщения страницы.
 */
@Serializable
data class ViewToolCallRequest(
    val connectionId: String,
    val name: String,
    val arguments: JsonObject,
)

/**
 * Результат вызова тула. [isError] — ошибка самого тула, а не транспорта: такой ответ приходит
 * `200` и странице отдаётся как успешный JSON-RPC result, иначе он потерял бы свой текст.
 */
@Serializable
data class ViewToolCallResultDto(
    val content: JsonElement? = null,
    val structuredContent: JsonElement? = null,
    @SerialName("isError") val isError: Boolean = false,
)
