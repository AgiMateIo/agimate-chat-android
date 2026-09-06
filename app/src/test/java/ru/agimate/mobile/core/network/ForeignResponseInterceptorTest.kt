package ru.agimate.mobile.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ForeignResponseInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(ForeignResponseInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun call() = client.newCall(Request.Builder().url(server.url("/user/user/me")).build())

    /**
     * Портал отвечает страницей входа с кодом 200. До этой проверки такой ответ доходил до
     * сериализатора и убивал процесс — из потока OkHttp его ловить некому.
     */
    @Test
    fun `a captive portal page becomes a connection error`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = "<!DOCTYPE html><html><body>Sign in to continue</body></html>",
            )
        )

        assertThrows(IOException::class.java) { call().execute() }
    }

    @Test
    fun `a normal json answer passes through`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"response":{"id":"1"}}""",
            )
        )

        call().execute().use { assertEquals(200, it.code) }
    }

    /** Кодировка в типе — обычное дело, и отличать по строке целиком было бы ошибкой. */
    @Test
    fun `json with a charset passes through`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json; charset=utf-8"),
                body = """{"response":null}""",
            )
        )

        call().execute().use { assertEquals(200, it.code) }
    }

    /** Ответ без тела законен: у него нет и типа содержимого. */
    @Test
    fun `an empty answer passes through`() {
        server.enqueue(MockResponse(code = 204))

        call().execute().use { assertEquals(204, it.code) }
    }

    /**
     * Ошибки разбирает `ApiException`, и текст ошибки бывает каким угодно. Подменять их сетевой
     * ошибкой значило бы прятать причину: 502 с html-страницей прокси — всё же ответ о сервере.
     */
    @Test
    fun `an html error page is left to the error handling`() {
        server.enqueue(
            MockResponse(
                code = 502,
                headers = headersOf("Content-Type", "text/html"),
                body = "<html><body>Bad gateway</body></html>",
            )
        )

        call().execute().use { assertEquals(502, it.code) }
    }
}
