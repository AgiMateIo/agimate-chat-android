package ru.agimate.mobile.data.agents

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson

/**
 * Слэш на конце `GET .../starters/` значим — без него 404. Подсказки агента и карточка с его типом
 * уходят параллельно, поэтому ответы раздаются по пути, а не очередью.
 */
class StartersRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: StartersRepository
    private var agentType = "GENERIC"
    private var hideCode = 200

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/control/manage/agents/a1" ->
                    ok("""{"response":{"id":"a1","name":"Селена","type":"$agentType"}}""")
                "/control/manage/agents/a1/starters/" -> ok(STARTERS)
                "/control/manage/agents/a1/starters/hidden" ->
                    if (hideCode == 200) ok("""{"response":null}""")
                    else MockResponse(code = hideCode, body = """{"error":{"message":"gone"}}""")
                else -> MockResponse(code = 404)
            }
        }
        server.start()
        repository = StartersRepository(
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(AgentsApi::class.java)
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test
    fun `a chat agent gets its greeting and the starters in server order`() = runTest {
        val starters = repository.forChat("a1")!!

        assertEquals("Я Селена — астролог и таролог.", starters.greeting)
        assertEquals(
            listOf("Вытяни карту дня", "Построй мою натальную карту"),
            starters.items.map { it.text },
        )
        assertFalse(starters.items[0].hideable)
        assertTrue(starters.items[1].hideable)
    }

    @Test
    fun `agents with an external brain get no starters`() = runTest {
        agentType = "MCP"
        assertNull(repository.forChat("a1"))
        agentType = "WEBHOOK"
        assertNull(repository.forChat("a1"))
    }

    @Test
    fun `hiding addresses the starter by its binding and text`() = runTest {
        repository.hide("a1", Starter("Построй мою натальную карту", "s1", "Астрология"))

        val request = generateSequence { server.takeRequest() }
            .first { it.target == "/control/manage/agents/a1/starters/hidden" }
        assertEquals("POST", request.method)
        assertEquals(
            """{"agentSkillId":"s1","text":"Построй мою натальную карту"}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `a starter the skill no longer has counts as hidden`() = runTest {
        hideCode = 404
        repository.hide("a1", Starter("Построй мою натальную карту", "s1", "Астрология"))
    }

    private companion object {
        val STARTERS = """
            {"response":{
              "greeting":"Я Селена — астролог и таролог.",
              "starters":[
                {"id":"o1","agentSkillId":null,"skillTitle":null,"text":"Вытяни карту дня"},
                {"id":null,"agentSkillId":"s1","skillTitle":"Астрология","text":"Построй мою натальную карту"}]}}
        """.trimIndent()
    }
}
