package ru.agimate.mobile.core.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.network.unwrap

/**
 * Две ловушки этого экрана — обе в путях. Список способов входа отвечает 404 без слэша на конце, а
 * провайдер едет в круг строчными и в отвязку заглавными: перепутать регистр значит получить отказ
 * там, где всё остальное правильно.
 */
class AuthMethodsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthMethodsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthMethodsApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test
    fun `the listing keeps its trailing slash`() = runTest {
        server.enqueue(ok("""{"response":[]}"""))
        api.methods()
        assertEquals("/user/auth/methods/", server.takeRequest().target)
    }

    @Test
    fun `unlinking names the provider in upper case`() = runTest {
        server.enqueue(ok("""{"response":"success"}"""))
        api.unlinkProvider(AuthProvider.GITHUB.name)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/user/auth/methods/oauth/GITHUB", request.target)
    }

    @Test
    fun `a provider entry may keep no address`() = runTest {
        server.enqueue(
            ok(
                """
                {"response":[
                  {"kind":"OAUTH","provider":"GITHUB","title":"GitHub","addedAt":"2026-08-15 21:05:21"},
                  {"kind":"PASSWORD","title":"Password","addedAt":"2026-08-20 10:00:00"}
                ]}
                """.trimIndent()
            )
        )

        val methods = api.methods().unwrap("способы входа")
        assertEquals(2, methods.size)
        // Привязанный вручную провайдер вправе адреса не сообщать — это не сломанный ответ.
        assertNull(methods[0].email)
        assertEquals("GITHUB", methods[0].provider)
        // Время у этого листинга через пробел, не по ISO: разбор должен пережить и такой вид.
        assertEquals("2026-08-15T21:05:21Z", methods[0].addedAt.toString())
        assertNull(methods[1].provider)
    }

    @Test
    fun `a refusal by substance still arrives with code 200`() = runTest {
        server.enqueue(ok("""{"response":{"provider":"GITHUB","outcome":"TAKEN"}}"""))

        val result = api.link(LinkProofRequest("3f2a")).unwrap("привязка")
        assertEquals("/user/auth/methods/link", server.takeRequest().target)
        assertEquals(LinkOutcome.TAKEN, LinkOutcome.of(result.outcome))
        assertEquals(AuthProvider.GITHUB, AuthProvider.of(result.provider))
    }

    @Test
    fun `an outcome this version does not know is not a success`() {
        assertEquals(LinkOutcome.UNKNOWN, LinkOutcome.of("SOMETHING_ELSE"))
        assertEquals(false, LinkOutcome.of("SOMETHING_ELSE").success)
        assertEquals(true, LinkOutcome.of("ALREADY_YOURS").success)
    }
}
