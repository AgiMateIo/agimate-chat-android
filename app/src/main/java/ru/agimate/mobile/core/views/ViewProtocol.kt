package ru.agimate.mobile.core.views

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.agimate.mobile.core.network.ApiJson
import java.net.URI

/**
 * Контекст хоста, который страница получает в ответ на `ui/initialize`.
 *
 * Тема и локаль — снимок на момент инициализации: страница сама не переключает тему приложения.
 */
data class ViewHostContext(
    val theme: String,
    val locale: String,
    val variables: Map<String, String>,
)

/**
 * Разрешённая ссылка для `ui/open-link`.
 *
 * Конструктор закрыт, единственный вход — [parse]: протащить адрес в действие мимо проверки
 * схемы и хоста структурно нельзя.
 */
@JvmInline
value class HttpsUrl private constructor(val value: String) {

    companion object {
        /**
         * Принимает только строку с абсолютным `https`-адресом и непустым хостом.
         *
         * Всё остальное — `http:`, `javascript:`, `data:`, адрес без хоста, не-строка — даёт
         * `null`: сообщение от страницы недоверенное, и открывать по нему что попало нельзя.
         */
        fun parse(raw: JsonElement?): HttpsUrl? {
            val primitive = raw as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val uri = runCatching { URI(primitive.content) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrBlank()) return null
            return HttpsUrl(primitive.content)
        }
    }
}

/** Действие, которое хост выполняет в ответ на сообщение страницы. */
sealed interface ViewAction {

    /** Готовый ответ JSON-RPC — отправляется странице как есть. */
    data class Reply(val reply: JsonObject) : ViewAction

    /** Страница инициализирована и прислала `ui/notifications/initialized`. */
    data object Ready : ViewAction

    /**
     * Страница просит высоту. В MVP iframe занимает весь экран, поэтому высота только
     * передаётся принимающей стороне и никуда не применяется.
     */
    data class Resize(val height: Double) : ViewAction

    /**
     * Вызов тула. Полей `agentId`/`connectionId` здесь нет намеренно: связку выбирает хост по
     * тому, какой вью открыт, — иначе страница могла бы вызвать тул чужого агента.
     */
    data class Call(val id: JsonElement, val name: String, val arguments: JsonObject) : ViewAction

    /** Просьба открыть ссылку во внешнем браузере. */
    data class OpenLink(val id: JsonElement, val url: HttpsUrl) : ViewAction

    /** Сообщение не требует реакции: нотификация, неизвестный метод или мусор. */
    data object Ignore : ViewAction
}

/**
 * Чистый редуктор MCP Apps: строка из `postMessage` → действие хоста.
 *
 * Ни Android, ни WebView здесь нет — поэтому весь протокол проверяется обычными JVM-тестами.
 */
object ViewProtocol {

    const val METHOD_NOT_FOUND = -32601
    const val INTERNAL_ERROR = -32603

    private val EmptyObject = JsonObject(emptyMap())

    /**
     * Разбирает одно сообщение JSON-RPC 2.0.
     *
     * Нотификации проверяются первыми: нотификация — это сообщение без валидного `id`, а `0` —
     * валидный id, поэтому `id` проверяется на наличие и тип, а не на истинность. Всё, что не
     * удалось разобрать, становится [ViewAction.Ignore]: слушатель `postMessage` не должен
     * падать от мусора и терять следующие сообщения.
     */
    fun decode(raw: String, host: ViewHostContext): ViewAction {
        val message = runCatching { ApiJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return ViewAction.Ignore
        if (message.string("jsonrpc") != "2.0") return ViewAction.Ignore
        val method = message.string("method") ?: return ViewAction.Ignore
        val params = message["params"] as? JsonObject ?: EmptyObject
        val id = message.rpcId()
        return if (id == null) {
            decodeNotification(method, params)
        } else {
            decodeRequest(id, method, params, host)
        }
    }

    /**
     * Результат `ui/initialize`: эхо версии протокола (если она пришла) и описание хоста.
     *
     * `hostCapabilities.serverTools` — пустой объект: серверные тулы страница получает из
     * `tools/call`, а не из списка возможностей хоста.
     */
    fun initializeResult(protocolVersion: JsonElement?, host: ViewHostContext): JsonObject =
        buildJsonObject {
            if (protocolVersion != null && protocolVersion !is JsonNull) {
                put("protocolVersion", protocolVersion)
            }
            putJsonObject("hostInfo") {
                put("name", "agimate")
                put("version", "1")
            }
            putJsonObject("hostCapabilities") {
                putJsonObject("serverTools") {}
            }
            putJsonObject("hostContext") {
                put("theme", host.theme)
                put("locale", host.locale)
                put("displayMode", "inline")
                putJsonArray("availableDisplayModes") { add("inline") }
                putJsonObject("styles") {
                    putJsonObject("variables") {
                        host.variables.forEach { (name, value) -> put(name, value) }
                    }
                }
            }
        }

    /** Успешный ответ: поля `id` и `result` сохраняются как пришли, без пересборки. */
    fun resultReply(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    fun errorReply(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    /**
     * Аргументы вызова для inline-вью. Нотификация, а не запрос: `id` нет.
     *
     * Хост шлёт её после `ui/notifications/initialized` и ровно один раз на экземпляр WebView.
     */
    fun toolInputNotification(arguments: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "ui/notifications/tool-input")
        putJsonObject("params") { put("arguments", arguments) }
    }

    /**
     * Результат вызова для inline-вью. `params` — `CallToolResult` как есть, без обёрток: страница
     * сама решает, как рисовать `isError`.
     */
    fun toolResultNotification(result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "ui/notifications/tool-result")
        put("params", result)
    }

    private fun decodeNotification(method: String, params: JsonObject): ViewAction =
        when (method) {
            "ui/notifications/initialized" -> ViewAction.Ready
            "ui/notifications/size-changed" -> {
                val height = (params["height"] as? JsonPrimitive)?.doubleOrNull
                if (height != null && height.isFinite() && height > 0.0) {
                    ViewAction.Resize(height)
                } else {
                    ViewAction.Ignore
                }
            }
            else -> ViewAction.Ignore
        }

    private fun decodeRequest(
        id: JsonElement,
        method: String,
        params: JsonObject,
        host: ViewHostContext,
    ): ViewAction = when (method) {
        "ui/initialize" -> ViewAction.Reply(
            resultReply(id, initializeResult(params["protocolVersion"], host)),
        )
        "ping" -> ViewAction.Reply(resultReply(id, EmptyObject))
        "tools/call" -> decodeToolCall(id, params)
        "ui/open-link" -> decodeOpenLink(id, params)
        else -> ViewAction.Reply(errorReply(id, METHOD_NOT_FOUND, "Method not found"))
    }

    private fun decodeToolCall(id: JsonElement, params: JsonObject): ViewAction {
        val name = params.string("name")?.takeIf { it.isNotBlank() }
            ?: return ViewAction.Reply(errorReply(id, INTERNAL_ERROR, "Missing tool name"))
        // Из сообщения берутся только name и arguments: остальные поля страница может прислать,
        // но они не попадают в действие и не влияют на выбор связки.
        val arguments = params["arguments"] as? JsonObject ?: EmptyObject
        return ViewAction.Call(id, name, arguments)
    }

    private fun decodeOpenLink(id: JsonElement, params: JsonObject): ViewAction {
        val url = HttpsUrl.parse(params["url"])
            ?: return ViewAction.Reply(
                errorReply(id, INTERNAL_ERROR, "Only https links can be opened"),
            )
        return ViewAction.OpenLink(id, url)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * `id` валиден, если поле есть и это строка или число. `0` — валидный id, поэтому проверка
     * на истинность здесь ошиблась бы; `null`, объект и массив делают сообщение нотификацией.
     */
    private fun JsonObject.rpcId(): JsonElement? {
        val id = this["id"] ?: return null
        val primitive = id as? JsonPrimitive ?: return null
        val valid = primitive.isString || primitive.doubleOrNull != null
        return if (valid) id else null
    }
}
