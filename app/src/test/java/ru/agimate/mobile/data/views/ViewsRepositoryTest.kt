package ru.agimate.mobile.data.views

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.ApiJson

/**
 * Слэш на конце `GET .../views/` значим — без него 404. У `tools/call` имя тула и аргументы
 * уходят вместе с connectionId, который берётся из нативного состояния, а не из сообщения
 * страницы: аргументы страницы могут содержать свой `connectionId`, и он не должен его подменить.
 */
class ViewsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ViewsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = ViewsRepository(
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ViewsApi::class.java),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test
    fun `list parses views and titles an unnamed connection by its connector code`() = runTest {
        server.enqueue(
            ok(
                """{"response":[
                    {"connectionId":"c1","connectorCode":"weather","connectionName":"Weather",
                     "uri":"ui://s/weather","tools":["get_weather"]},
                    {"connectionId":"c2","connectorCode":"persist-memory",
                     "uri":"ui://s/memory","tools":["remember","recall"]}]}""",
            ),
        )

        val views = repository.list("a1")

        assertEquals("/control/manage/agents/a1/views/", server.takeRequest().target)
        assertEquals(listOf("Weather", "persist-memory"), views.map { it.title })
        assertEquals(listOf("get_weather"), views[0].tools)
        assertEquals(listOf("remember", "recall"), views[1].tools)
        assertEquals("ui://s/weather", views[0].uri)
        assertEquals("c2", views[1].connectionId)
    }

    @Test
    fun `content sends uri as an encoded query parameter`() = runTest {
        server.enqueue(
            ok(
                """{"response":{"uri":"ui://s/weather","mimeType":"text/html;profile=mcp-app",
                    "html":"<html>sun</html>"}}""",
            ),
        )

        val content = repository.content("a1", "c1", "ui://s/weather")

        val request = server.takeRequest()
        assertEquals("/control/manage/agents/a1/views/content", request.url.encodedPath)
        assertEquals("c1", request.url.queryParameter("connectionId"))
        assertEquals("ui://s/weather", request.url.queryParameter("uri"))
        assertEquals("<html>sun</html>", content.html)
        assertEquals("text/html;profile=mcp-app", content.mimeType)
    }

    @Test
    fun `callTool sends the connection at the top level`() = runTest {
        server.enqueue(ok("""{"response":{"content":[{"type":"text","text":"ok"}],"isError":false}}"""))

        repository.callTool(
            agentId = "a1",
            connectionId = "c1",
            name = "get_weather",
            arguments = buildJsonObject {
                put("city", "Moscow")
                put("connectionId", "evil")
            },
        )

        val request = server.takeRequest()
        assertEquals("/control/manage/agents/a1/views/tools/call", request.target)
        assertEquals("POST", request.method)
        assertEquals(
            """{"connectionId":"c1","name":"get_weather","arguments":{"city":"Moscow","connectionId":"evil"}}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `callTool keeps the mcp result verbatim`() = runTest {
        server.enqueue(
            ok(
                """{"response":{"content":[{"type":"text","text":"ok"}],
                    "structuredContent":{"temp":21},"isError":true}}""",
            ),
        )

        val result = repository.callTool(
            "a1",
            "c1",
            "get_weather",
            buildJsonObject { put("city", "Moscow") },
        )

        assertEquals(JsonPrimitive(true), result["isError"])
        assertNotNull(result["content"])
        assertEquals(buildJsonObject { put("temp", 21) }, result["structuredContent"])

        // Без structuredContent ключа быть не должно: страница отличает «нет данных» от пустого объекта.
        server.enqueue(ok("""{"response":{"content":[{"type":"text","text":"ok"}],"isError":false}}"""))

        val withoutStructured = repository.callTool(
            "a1",
            "c1",
            "get_weather",
            buildJsonObject { put("city", "Moscow") },
        )

        assertFalse(withoutStructured.containsKey("structuredContent"))
        assertEquals(JsonPrimitive(false), withoutStructured["isError"])
    }

    @Test
    fun `a 502 keeps the server message`() = runTest {
        server.enqueue(
            MockResponse(code = 502, body = """{"error":{"message":"server did not answer"}}"""),
        )

        val error = runCatching {
            repository.callTool("a1", "c1", "get_weather", buildJsonObject { })
        }.exceptionOrNull()

        assertTrue("expected ApiException.Server, got $error", error is ApiException.Server)
        error as ApiException.Server
        assertEquals(502, error.code)
        assertTrue(error.message.orEmpty().contains("server did not answer"))
    }

    @Test
    fun `toolCall parses the view, input and result`() = runTest {
        server.enqueue(
            ok(
                """{"response":{
                    "toolCallId":"c2","name":"show_memory","connectionId":"k1",
                    "viewUri":"ui://persist-memory/memory",
                    "input":{"query":"all"},
                    "result":{"content":[{"type":"text","text":"ok"}],"isError":false}}}""",
            ),
        )

        val call = repository.toolCall("s1", "c2")

        assertEquals("/control/manage/sessions/s1/tool-calls/c2", server.takeRequest().target)
        assertEquals("c2", call.toolCallId)
        assertEquals("show_memory", call.name)
        assertEquals("k1", call.connectionId)
        assertEquals("ui://persist-memory/memory", call.viewUri)
        assertEquals(buildJsonObject { put("query", "all") }, call.input)
        assertEquals(JsonPrimitive(false), call.result?.get("isError"))
        assertNotNull(call.result?.get("content"))
    }

    /**
     * У вызова без вью и без результата отсутствующие поля — это отсутствие ключа, а не `null`:
     * страница получает `input` всегда, хотя бы пустым объектом.
     */
    @Test
    fun `toolCall without input gives an empty object and a null result`() = runTest {
        server.enqueue(ok("""{"response":{"toolCallId":"c1","name":"get_memory"}}"""))

        val call = repository.toolCall("s1", "c1")

        assertEquals(buildJsonObject { }, call.input)
        assertNull(call.viewUri)
        assertNull(call.connectionId)
        assertNull(call.result)
    }

    @Test
    fun `content carries the granted csp`() = runTest {
        server.enqueue(
            ok(
                """{"response":{"uri":"ui://x","mimeType":"text/html","html":"<html></html>",
                    "csp":{"resourceDomains":["https://esm.sh"],
                           "connectDomains":["https://api.example.com"]}}}""",
            ),
        )

        val content = repository.content("a1", "c1", "ui://x")

        assertEquals(listOf("https://esm.sh"), content.externalDomains?.resourceDomains)
        assertEquals(listOf("https://api.example.com"), content.externalDomains?.connectDomains)
    }

    @Test
    fun `content without csp has no external domains`() = runTest {
        server.enqueue(
            ok("""{"response":{"uri":"ui://x","mimeType":"text/html","html":"<html></html>"}}"""),
        )

        assertNull(repository.content("a1", "c1", "ui://x").externalDomains)
    }
}
