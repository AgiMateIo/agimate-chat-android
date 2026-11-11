package ru.agimate.mobile.core.views

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Редуктор протокола: страница присылает строки, хост отвечает действиями. Тесты держат таблицу
 * сообщений из спеки целиком — по одному тесту на строку, включая битые сообщения.
 */
class ViewProtocolTest {

    private val host = ViewHostContext(
        theme = "dark",
        locale = "ru-RU",
        variables = mapOf(
            "--color-background-primary" to "#101010",
            "--font-sans" to "sans-serif",
        ),
    )

    @Test
    fun `initialize answers with the host context and echoes the protocol version`() {
        val action = ViewProtocol.decode(
            """{"jsonrpc":"2.0","id":1,"method":"ui/initialize","params":{"protocolVersion":"2026-01-26"}}""",
            host,
        )

        val reply = replyOf(action)
        assertEquals(JsonPrimitive("2.0"), reply["jsonrpc"])
        assertEquals(JsonPrimitive(1), reply["id"])

        val result = reply.getValue("result").jsonObject
        assertEquals(JsonPrimitive("2026-01-26"), result["protocolVersion"])
        assertEquals(
            buildJsonObject {
                put("name", "agimate")
                put("version", "1")
            },
            result["hostInfo"],
        )
        assertEquals(
            buildJsonObject { putJsonObject("serverTools") {} },
            result["hostCapabilities"],
        )

        val context = result.getValue("hostContext").jsonObject
        assertEquals(JsonPrimitive("dark"), context["theme"])
        assertEquals(JsonPrimitive("ru-RU"), context["locale"])
        assertEquals(JsonPrimitive("inline"), context["displayMode"])
        assertEquals(JsonArray(listOf(JsonPrimitive("inline"))), context["availableDisplayModes"])
        assertEquals(
            buildJsonObject {
                put("--color-background-primary", "#101010")
                put("--font-sans", "sans-serif")
            },
            context.getValue("styles").jsonObject["variables"],
        )
    }

    @Test
    fun `initialized notification is ready`() {
        assertEquals(
            ViewAction.Ready,
            ViewProtocol.decode("""{"jsonrpc":"2.0","method":"ui/notifications/initialized"}""", host),
        )
    }

    @Test
    fun `size changed with a usable height resizes`() {
        assertEquals(
            ViewAction.Resize(320.5),
            ViewProtocol.decode(
                """{"jsonrpc":"2.0","method":"ui/notifications/size-changed","params":{"height":320.5}}""",
                host,
            ),
        )
    }

    @Test
    fun `size changed without a usable height is ignored`() {
        val messages = listOf(
            // NaN в JSON нет — строка проверяет isFinite, а переполнение литерала — тот же случай.
            """{"jsonrpc":"2.0","method":"ui/notifications/size-changed","params":{"height":"NaN"}}""",
            """{"jsonrpc":"2.0","method":"ui/notifications/size-changed","params":{"height":1e400}}""",
            """{"jsonrpc":"2.0","method":"ui/notifications/size-changed","params":{"height":0}}""",
            """{"jsonrpc":"2.0","method":"ui/notifications/size-changed","params":{}}""",
        )

        messages.forEach { raw ->
            assertEquals(raw, ViewAction.Ignore, ViewProtocol.decode(raw, host))
        }
    }

    @Test
    fun `ping gets an empty result`() {
        val reply = replyOf(
            ViewProtocol.decode("""{"jsonrpc":"2.0","id":"p","method":"ping"}""", host),
        )

        assertEquals(buildJsonObject {}, reply["result"])
    }

    @Test
    fun `tools call keeps id zero and ignores a page connection`() {
        val raw = """
            {"jsonrpc":"2.0","id":0,"method":"tools/call",
             "params":{"name":"get_weather","connectionId":"evil","arguments":{"city":"Moscow"}}}
        """.trimIndent()

        assertEquals(
            ViewAction.Call(
                id = JsonPrimitive(0),
                name = "get_weather",
                arguments = buildJsonObject { put("city", "Moscow") },
            ),
            ViewProtocol.decode(raw, host),
        )
    }

    @Test
    fun `tools call without a name is an internal error`() {
        val reply = replyOf(
            ViewProtocol.decode("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{}}""", host),
        )

        assertError(reply, ViewProtocol.INTERNAL_ERROR, "Missing tool name")
    }

    @Test
    fun `open link accepts https and rejects everything else`() {
        val accepted = ViewProtocol.decode(openLink(JsonPrimitive("https://example.com/a")), host)
        assertTrue("expected OpenLink, got $accepted", accepted is ViewAction.OpenLink)
        accepted as ViewAction.OpenLink
        assertEquals(JsonPrimitive(3), accepted.id)
        assertEquals("https://example.com/a", accepted.url.value)

        val rejected = listOf<JsonElement>(
            JsonPrimitive("http://example.com/a"),
            JsonPrimitive("javascript:alert(1)"),
            JsonPrimitive("data:text/html,hello"),
            JsonPrimitive("https:example.com"),
            JsonPrimitive("https://"),
            // Относительные ссылки: у URI нет схемы, и проверка схемы не должна падать.
            JsonPrimitive("/relative/path"),
            JsonPrimitive("//example.com/a"),
            JsonPrimitive(42),
        )
        rejected.forEach { url ->
            val reply = replyOf(ViewProtocol.decode(openLink(url), host))
            assertError(reply, ViewProtocol.INTERNAL_ERROR, "Only https links can be opened", url.toString())
        }
    }

    @Test
    fun `an unknown request gets method not found`() {
        val reply = replyOf(
            ViewProtocol.decode("""{"jsonrpc":"2.0","id":"abc","method":"resources/read","params":{}}""", host),
        )

        assertEquals(JsonPrimitive("abc"), reply["id"])
        assertError(reply, ViewProtocol.METHOD_NOT_FOUND, "Method not found")
    }

    @Test
    fun `an unknown notification is ignored`() {
        assertEquals(
            ViewAction.Ignore,
            ViewProtocol.decode("""{"jsonrpc":"2.0","method":"notifications/message"}""", host),
        )
    }

    @Test
    fun `malformed messages are ignored`() {
        val messages = listOf(
            "not json at all",
            """{"jsonrpc":"1.0","id":1,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"method":42}""",
            """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""",
        )

        messages.forEach { raw ->
            assertEquals(raw, ViewAction.Ignore, ViewProtocol.decode(raw, host))
        }
    }

    @Test
    fun `result and error replies keep the id verbatim`() {
        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "abc")
                put("result", buildJsonObject {})
            },
            ViewProtocol.resultReply(JsonPrimitive("abc"), buildJsonObject {}),
        )

        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 0)
                putJsonObject("error") {
                    put("code", ViewProtocol.METHOD_NOT_FOUND)
                    put("message", "Method not found")
                }
            },
            ViewProtocol.errorReply(JsonPrimitive(0), ViewProtocol.METHOD_NOT_FOUND, "Method not found"),
        )
    }

    @Test
    fun `tool-input names the method and wraps arguments in params`() {
        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "ui/notifications/tool-input")
                putJsonObject("params") {
                    putJsonObject("arguments") { put("city", "Moscow") }
                }
            },
            ViewProtocol.toolInputNotification(buildJsonObject { put("city", "Moscow") }),
        )
    }

    @Test
    fun `tool-result puts the call result itself into params`() {
        val result = buildJsonObject {
            putJsonObject("structuredContent") { put("temp", 21) }
            put("isError", false)
        }

        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "ui/notifications/tool-result")
                put("params", result)
            },
            ViewProtocol.toolResultNotification(result),
        )
    }

    private fun replyOf(action: ViewAction): JsonObject {
        assertTrue("expected Reply, got $action", action is ViewAction.Reply)
        return (action as ViewAction.Reply).reply
    }

    private fun assertError(
        reply: JsonObject,
        code: Int,
        message: String,
        context: String? = null,
    ) {
        val error = reply.getValue("error").jsonObject
        val prefix = if (context == null) "" else "$context: "
        assertEquals("${prefix}code", JsonPrimitive(code), error["code"])
        assertEquals("${prefix}message", JsonPrimitive(message), error["message"])
    }

    private fun openLink(url: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 3)
        put("method", "ui/open-link")
        putJsonObject("params") { put("url", url) }
    }.toString()
}
