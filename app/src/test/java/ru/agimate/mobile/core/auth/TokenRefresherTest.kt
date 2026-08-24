package ru.agimate.mobile.core.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import javax.inject.Provider

/**
 * Самая дорогая ошибка в этом приложении — неверная работа с ротацией refresh-токена: она либо
 * разлогинивает человека на ровном месте, либо гасит ему сессию как кражу. Поэтому здесь проверяется
 * именно поведение, а не форма запроса.
 */
class TokenRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    private val initial = AuthTokens("access-1", "refresh-1", "session-1")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            // Свои повторы OkHttp здесь мешают: проверяется логика рефрешера, а не транспорта.
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun refresher(
        store: TokenStore,
        clock: TokenRefresher.Clock = TokenRefresher.Clock.System,
    ) = TokenRefresher(store, Provider { api }, clock)

    private fun tokenResponse(access: String, refresh: String) = MockResponse(
        code = 200,
        body = """
            {"response":{"accessToken":"$access","refreshToken":"$refresh",
             "refreshTokenId":"rid","expiresIn":3600,"sessionId":"session-1"}}
        """.trimIndent(),
    )

    @Test
    fun `renewal ahead of time waits for nine tenths of the lifetime`() = runTest {
        // Час жизни: обновляться пора в 3 240 000, не раньше.
        val store = FakeTokenStore(initial.copy(renewAtMillis = 3_240_000))
        val refresher = refresher(store, clock = { 3_239_000 })

        val outcome = refresher.refreshIfDue()

        assertEquals(0, server.requestCount)
        assertEquals(TokenRefresher.Outcome.Refreshed("access-1"), outcome)
    }

    @Test
    fun `renewal ahead of time happens once the moment has come`() = runTest {
        val store = FakeTokenStore(initial.copy(renewAtMillis = 3_240_000))
        server.enqueue(tokenResponse("access-2", "refresh-2"))

        val outcome = refresher(store, clock = { 3_240_001 }).refreshIfDue()

        assertEquals(1, server.requestCount)
        assertEquals(TokenRefresher.Outcome.Refreshed("access-2"), outcome)
    }

    @Test
    fun `a pair saved without a lifetime is left to the 401 path`() = runTest {
        // Так выглядит пара, сохранённая прошлой версией приложения: срока рядом с ней нет, и
        // выдумывать его нельзя — обновление по 401 работало и без него.
        val outcome = refresher(FakeTokenStore(initial), clock = { Long.MAX_VALUE }).refreshIfDue()

        assertEquals(0, server.requestCount)
        assertEquals(TokenRefresher.Outcome.Refreshed("access-1"), outcome)
    }

    @Test
    fun `parallel 401s produce a single refresh request`() = runTest {
        val store = FakeTokenStore(initial)
        server.enqueue(tokenResponse("access-2", "refresh-2"))

        // Один рефрешер на всех — именно он и обязан свести пять попыток в одну.
        val refresher = refresher(store)
        val outcomes = List(5) { async { refresher.refresh("access-1") } }.awaitAll()

        // Пять параллельных попыток — но обновление однопоточное, значит на сервер ушёл один запрос.
        assertEquals(1, server.requestCount)
        assertTrue(outcomes.all { it is TokenRefresher.Outcome.Refreshed })
        assertEquals("access-2", store.load()?.accessToken)
    }

    @Test
    fun `waiter that lost the race retries with the already refreshed token`() = runTest {
        val store = FakeTokenStore(initial)
        val refresher = refresher(store)
        server.enqueue(tokenResponse("access-2", "refresh-2"))

        val first = refresher.refresh("access-1")
        // Второй пришёл со старым токеном, когда обновление уже случилось — запрос не нужен.
        val second = refresher.refresh("access-1")

        assertEquals(1, server.requestCount)
        assertEquals(TokenRefresher.Outcome.Refreshed("access-2"), first)
        assertEquals(TokenRefresher.Outcome.Refreshed("access-2"), second)
    }

    @Test
    fun `409 is retried, not treated as a logout`() = runTest {
        val store = FakeTokenStore(initial)
        server.enqueue(MockResponse(code = 409, body = """{"error":{"message":"concurrent"}}"""))
        server.enqueue(tokenResponse("access-2", "refresh-2"))

        val outcome = refresher(store).refresh("access-1")

        assertEquals(TokenRefresher.Outcome.Refreshed("access-2"), outcome)
        assertFalse("409 не должен разлогинивать", store.cleared)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `403 wipes the tokens and sends the user to sign-in`() = runTest {
        val store = FakeTokenStore(initial)
        server.enqueue(MockResponse(code = 403, body = """{"error":{"message":"revoked"}}"""))

        val outcome = refresher(store).refresh("access-1")

        assertEquals(TokenRefresher.Outcome.SessionDead, outcome)
        assertTrue(store.cleared)
        assertEquals(null, store.load())
    }

    @Test
    fun `401 on refresh is also a dead session`() = runTest {
        val store = FakeTokenStore(initial)
        server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"no token"}}"""))

        assertEquals(TokenRefresher.Outcome.SessionDead, refresher(store).refresh("access-1"))
        assertTrue(store.cleared)
    }

    @Test
    fun `lost response is retried with the very same refresh token`() = runTest {
        val store = FakeTokenStore(initial)
        // Ответ не доехал: соединение оборвано на старте запроса.
        server.enqueue(
            MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()
        )
        server.enqueue(tokenResponse("access-2", "refresh-2"))

        val outcome = refresher(store).refresh("access-1")

        assertEquals(TokenRefresher.Outcome.Refreshed("access-2"), outcome)
        assertFalse("сетевая ошибка — не повод начинать вход заново", store.cleared)

        // Обе попытки обязаны нести один и тот же токен: сервер держит окно на повтор ровно для
        // этого, а новый токен взять неоткуда.
        val bodies = List(server.requestCount) { server.takeRequest().body?.utf8().orEmpty() }
        // Оборванный запрос сервер дочитать не успел — его тело пустое; смотрим на доехавшие.
        val delivered = bodies.filter { it.isNotBlank() }
        assertTrue("ни один запрос не доехал", delivered.isNotEmpty())
        assertTrue(delivered.toString(), delivered.all { it.contains("refresh-1") })
    }

    @Test
    fun `outside the retry window a lost response stops being retried`() = runTest {
        val store = FakeTokenStore(initial)
        // Часы прыгают на две минуты вперёд сразу после первой попытки: окно повтора закрыто.
        var calls = 0
        val clock = TokenRefresher.Clock { if (calls++ == 0) 0L else 120_000L }
        server.enqueue(
            MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()
        )

        val outcome = refresher(store, clock).refresh("access-1")

        assertEquals(TokenRefresher.Outcome.Offline, outcome)
        assertFalse("токены целы: запрос мог не дойти до сервера вовсе", store.cleared)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `without stored tokens there is nothing to refresh`() = runTest {
        val store = FakeTokenStore(null)
        assertEquals(TokenRefresher.Outcome.NotSignedIn, refresher(store).refresh(null))
        assertEquals(0, server.requestCount)
    }
}
