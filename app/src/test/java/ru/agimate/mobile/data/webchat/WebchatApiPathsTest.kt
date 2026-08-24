package ru.agimate.mobile.data.webchat

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson

/**
 * Слэш в конце пути значим: `GET .../sessions/` и `POST .../sessions` — разные маршруты, и лишний
 * или недостающий слэш даёт 404. Ошибка тихая — 404 легко списать на «нет данных», — поэтому пути
 * зафиксированы тестом.
 */
class WebchatApiPathsTest {

    private lateinit var server: MockWebServer
    private lateinit var api: WebchatApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WebchatApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private fun target(): String = server.takeRequest().target

    private fun method(): String = server.takeRequest().method

    @Test
    fun `contacts listing keeps its trailing slash`() = runTest {
        server.enqueue(ok("""{"response":{"content":[],"number":0,"size":50,"totalElements":0,"totalPages":0}}"""))
        api.contacts(page = 0, size = 50)
        assertEquals("/control/manage/webchat/contacts/?page=0&size=50", target())
    }

    @Test
    fun `sessions listing keeps its trailing slash`() = runTest {
        server.enqueue(ok("""{"response":{"content":[],"number":0,"size":50,"totalElements":0,"totalPages":0}}"""))
        api.sessions(agentId = "a-1", page = 0, size = 50)
        assertEquals("/control/manage/webchat/sessions/?agentId=a-1&page=0&size=50", target())
    }

    @Test
    fun `starting a session has no trailing slash`() = runTest {
        server.enqueue(ok("""{"response":{"sessionId":"s-1"}}"""))
        api.startSession(StartSessionRequest("a-1"))
        val request = server.takeRequest()
        assertEquals("/control/manage/webchat/sessions", request.target)
        assertEquals("POST", request.method)
    }

    @Test
    fun `messages history keeps its trailing slash`() = runTest {
        server.enqueue(ok("""{"response":{"content":[],"number":0,"size":50,"totalElements":0,"totalPages":0}}"""))
        api.messages(sessionId = "s-1", page = 1, size = 50)
        assertEquals("/control/manage/webchat/sessions/s-1/messages/?page=1&size=50", target())
    }

    @Test
    fun `sending a message has no trailing slash`() = runTest {
        server.enqueue(ok("""{"response":{"sessionId":"s-1","messageId":"m-1"}}"""))
        api.sendMessage("s-1", SendMessageRequest(text = "привет"))
        assertEquals("/control/manage/webchat/sessions/s-1/messages", target())
    }

    @Test
    fun `read pointer goes to the read endpoint`() = runTest {
        server.enqueue(ok("""{"response":null}"""))
        api.markRead("s-1", MarkReadRequest("10000000-0000-7000-8000-000000000005"))
        val request = server.takeRequest()
        assertEquals("/control/manage/webchat/sessions/s-1/read", request.target)
        // Передаётся id строки, а не messageId — иначе 400.
        assertEquals(
            """{"lastReadMessageId":"10000000-0000-7000-8000-000000000005"}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `empty read body marks the whole session read`() = runTest {
        server.enqueue(ok("""{"response":null}"""))
        api.markRead("s-1", MarkReadRequest(null))
        assertEquals("{}", server.takeRequest().body?.utf8())
    }

    @Test
    fun `cancel goes by session, not by run`() = runTest {
        server.enqueue(ok("""{"response":{"sessionId":"s-1","cancelled":2}}"""))
        api.cancelSession("s-1")
        assertEquals("/control/manage/runs/sessions/s-1/cancel", target())
    }

    @Test
    fun `user channel token endpoint`() = runTest {
        server.enqueue(ok("""{"response":{"connectionToken":"c","subscriptionToken":"s","channel":"user:1","wsUrl":"ws://x/connection/websocket"}}"""))
        api.userToken()
        assertEquals("/control/manage/centrifugo/token", target())
    }

    @Test
    fun `closing a session is a DELETE on the session itself`() = runTest {
        server.enqueue(ok("""{"response":{"sessionId":"s-1"}}"""))
        api.closeSession("s-1")
        assertEquals("DELETE", method())
    }
}
