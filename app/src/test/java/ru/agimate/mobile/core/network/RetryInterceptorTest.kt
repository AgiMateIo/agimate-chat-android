package ru.agimate.mobile.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RetryInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get() = Request.Builder().url(server.url("/listing")).build()

    private fun post() = Request.Builder()
        .url(server.url("/messages"))
        .post("{}".toRequestBody())
        .build()

    @Test
    fun `a transient failure on a listing is retried`() {
        server.enqueue(MockResponse(code = 502))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        client.newCall(get()).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals("вторая попытка должна была уйти на сервер", 2, server.requestCount)
    }

    @Test
    fun `a rate limit is retried too`() {
        server.enqueue(MockResponse(code = 429))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        client.newCall(get()).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `attempts are bounded and the last response is returned`() {
        repeat(5) { server.enqueue(MockResponse(code = 503)) }

        client.newCall(get()).execute().use { response ->
            assertEquals(503, response.code)
        }
        assertEquals("попыток должно быть ровно три", 3, server.requestCount)
    }

    /**
     * Главное правило. Отправка сообщения — POST, и синхронная: таймаут прокси легко принять за
     * сбой, а повтор задвоил бы реплику в переписке.
     */
    @Test
    fun `a post is never retried`() {
        server.enqueue(MockResponse(code = 502))

        client.newCall(post()).execute().use { response ->
            assertEquals(502, response.code)
        }
        assertEquals("POST повторять нельзя", 1, server.requestCount)
    }

    @Test
    fun `a client error is not retried`() {
        server.enqueue(MockResponse(code = 404))

        client.newCall(get()).execute().use { response ->
            assertEquals(404, response.code)
        }
        assertEquals(1, server.requestCount)
    }
}
