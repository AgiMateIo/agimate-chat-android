package ru.agimate.mobile.data.user

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
 * Слэш на конце `GET /user/sessions/` значим — без него 404, и 404 легко списать на «нет данных».
 * У подписок слэша нет. Разбор ответа проверяем здесь же: блок `push` приехал в готовый листинг, и
 * старые поля от него поехать не должны.
 */
class UserApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: UserApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(UserApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test
    fun `sessions listing keeps its trailing slash`() = runTest {
        server.enqueue(ok("""{"response":[]}"""))
        api.sessions()
        assertEquals("/user/sessions/", server.takeRequest().target)
    }

    @Test
    fun `revoking a session goes to the row id`() = runTest {
        server.enqueue(ok("""{"response":"success"}"""))
        api.revokeSession("01a00699-8e6e-752e-a6b3-62e110f6f237")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/user/sessions/01a00699-8e6e-752e-a6b3-62e110f6f237", request.target)
    }

    @Test
    fun `a row carries its push subscriptions`() = runTest {
        server.enqueue(
            ok(
                """
                {"response":[
                  {"id":"01a00699-8e6e-752e-a6b3-62e110f6f237",
                   "client":"NATIVE",
                   "deviceLabel":"Pixel 8",
                   "createdAt":"2026-08-15T21:05:21.903002",
                   "lastSeenAt":"2026-08-19T08:41:02.117441",
                   "push":[
                     {"provider":"RUSTORE",
                      "maskedToken":"cV8kQz1p…",
                      "lastSeenAt":"2026-08-19T08:41:03.554120"}]}]}
                """.trimIndent()
            )
        )

        val row = api.sessions().unwrap("список устройств").single()

        assertEquals("Pixel 8", row.deviceLabel)
        assertEquals("NATIVE", row.client)
        assertEquals("RUSTORE", row.push.single().provider)
        assertEquals("cV8kQz1p…", row.push.single().maskedToken)
        assertTrue(row.push.single().lastSeenAt != null)
    }

    /** Браузерный вход приходит в том же списке, и подписок у него нет. */
    @Test
    fun `a row without push parses as no notifications`() = runTest {
        server.enqueue(ok("""{"response":[{"id":"s-1","client":"WEB"}]}"""))

        val row = api.sessions().unwrap("список устройств").single()

        assertEquals(emptyList<PushSubscriptionDto>(), row.push)
        assertEquals(null, row.deviceLabel)
    }
}
