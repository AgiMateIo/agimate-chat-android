package ru.agimate.mobile.core.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.network.unwrap

/**
 * Вход по паролю и письма. Проверяется то, что формой не видно: приложение обязано представиться
 * `NATIVE`, иначе refresh уйдёт в cookie, которой у приложения нет, и вход кончится парой без
 * второй половины.
 */
class AuthApiPathsTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `signing in says it is a native client`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    {"response":{"accessToken":"a","refreshToken":"r","expiresIn":3600,
                     "sessionId":"s"}}
                """.trimIndent(),
            )
        )

        val response = api.login(PasswordLoginRequest("user@example.com", "secret", "Pixel 8"))
            .unwrap("вход")

        val request = server.takeRequest()
        assertEquals("/user/auth/login", request.target)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body, body.contains("\"client\":\"NATIVE\""))
        assertTrue(body, body.contains("\"deviceName\":\"Pixel 8\""))
        assertEquals(3600, response.expiresIn)
    }

    @Test
    fun `the lifetime of the pair comes from the answer, not from a constant`() {
        val dto = AuthResponseDto(
            accessToken = "a",
            refreshToken = "r",
            expiresIn = 3600,
            sessionId = "s",
        )

        // Девять десятых часа: обновляемся заранее, но не на каждом запросе.
        assertEquals(1_000L + 3_240_000L, dto.toTokens(1_000L).renewAtMillis)
        // Сервер промолчал о сроке — выдумывать его нельзя, остаётся путь через 401.
        assertEquals(0L, dto.copy(expiresIn = 0).toTokens(1_000L).renewAtMillis)
    }

    @Test
    fun `both letters go to their own paths`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"response":"ok"}"""))
        api.register(RegisterRequest("user@example.com", "Eugene"))
        assertEquals("/user/auth/register", server.takeRequest().target)

        server.enqueue(MockResponse(code = 200, body = """{"response":"ok"}"""))
        api.forgotPassword(EmailRequest("user@example.com"))
        assertEquals("/user/auth/password/forgot", server.takeRequest().target)
    }
}
