package ru.agimate.mobile.feature.createagent

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.data.agents.AgentsApi
import ru.agimate.mobile.data.webchat.WebchatApi
import ru.agimate.mobile.data.webchat.WebchatRepository
import java.util.concurrent.TimeUnit

/**
 * Мастер обязан не только создать агента с навыками пресета, но и открыть их коннекторы: привязка
 * навыка сама ничего не открывает, а навык с неоткрытым коннектором сервер считает
 * неудовлетворённым и агенту не отдаёт вовсе. Пропущенный шаг виден только в разговоре — агент
 * молча ведёт себя как пустой, — поэтому цепочка запросов зафиксирована тестом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateAgentViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AgentsApi
    private lateinit var webchat: WebchatRepository

    /** Сколько первых открытий коннектора уронить: так проверяется повтор после ошибки. */
    private var bindFailures = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/agent-presets/") -> ok(PRESETS)
                    path.endsWith("/connections/") -> if (bindFailures-- > 0) {
                        MockResponse(code = 503)
                    } else {
                        ok("""{"response":{"id":"binding-1"}}""")
                    }
                    path.endsWith("/agents/") -> ok(CREATED)
                    path.endsWith("/webchat/sessions") ->
                        ok("""{"response":{"id":"s-1","agentId":"agent-1"}}""")
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(AgentsApi::class.java)
        webchat = WebchatRepository(retrofit.create(WebchatApi::class.java))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private fun requests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    private suspend fun readyViewModel(): CreateAgentViewModel {
        val vm = CreateAgentViewModel(api, webchat)
        val preset = vm.state.first { it.presets.isNotEmpty() }.presets.single()
        vm.select(preset)
        return vm
    }

    @Test
    fun `creation binds the preset skills and opens their connectors`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = readyViewModel()

        val created = CompletableDeferred<CreatedAgent>()
        vm.create { created.complete(it) }
        val result = created.await()

        assertEquals("agent-1", result.agentId)
        assertEquals("s-1", result.sessionId)

        val calls = requests()
        assertEquals(
            listOf(
                "/control/manage/agent-presets/",
                "/control/manage/agents/",
                "/control/manage/agents/agent-1/connections/",
                "/control/manage/agents/agent-1/connections/",
                "/control/manage/webchat/sessions",
            ),
            calls.map { it.url.encodedPath },
        )

        val create = calls[1].body?.utf8().orEmpty()
        assertTrue(create, create.contains(""""skillIds":["s-time","s-memory"]"""))
        assertTrue(create, create.contains(""""presetName":"personal-assistant""""))
        assertTrue(create, create.contains(""""type":"GENERIC""""))

        // Коннектор открывается по коду: инстанс внутреннего один на пользователя, и до первой
        // привязки id у него ещё нет — его подставляет сервер.
        assertEquals("""{"connectorCode":"time"}""", calls[2].body?.utf8())
        assertEquals("""{"connectorCode":"persist-memory"}""", calls[3].body?.utf8())
    }

    @Test
    fun `retry after a failed connector does not create a second agent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        bindFailures = 1
        val vm = readyViewModel()

        vm.create { }
        val failed = vm.state.first { it.createError != null }
        assertNotNull(failed.createdAgentId)

        val created = CompletableDeferred<CreatedAgent>()
        vm.create { created.complete(it) }
        assertEquals("agent-1", created.await().agentId)

        val creates = requests().count { it.url.encodedPath == "/control/manage/agents/" }
        assertEquals(1, creates)
    }

    private companion object {
        const val PRESETS = """
            {"response":[{"id":"p-1","name":"personal-assistant","title":"Личный ассистент",
            "description":"Помощник на каждый день","instructions":"Ты — личный ассистент.",
            "skills":[{"id":"s-time","name":"time"},{"id":"s-memory","name":"persist-memory"}],
            "connectorCodes":["time","persist-memory"],"agentType":"GENERIC","sortOrder":0,
            "enabled":true}]}
        """

        const val CREATED = """
            {"response":{"agent":{"id":"agent-1","name":"Личный ассистент"},"fullKey":"agk_secret"}}
        """
    }
}
