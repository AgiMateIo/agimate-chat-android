package ru.agimate.mobile.feature.views

import androidx.lifecycle.SavedStateHandle
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.R
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.core.views.ViewExternalDomains
import ru.agimate.mobile.data.views.AgentView
import ru.agimate.mobile.data.views.ViewsApi
import ru.agimate.mobile.data.views.ViewsRepository
import java.util.concurrent.TimeUnit

/**
 * Состояние экрана вью: список, открытая страница и её перезагрузка.
 *
 * Перезагрузка проверяется отдельно от загрузки: хост пересоздаётся по `revision`, и ревизия
 * обязана расти даже тогда, когда сервер вернул тот же самый HTML, — иначе «Обновить» на
 * неизменившейся странице не сделал бы ничего. Привязка вызова тула берётся из нативного
 * состояния, а не из аргументов страницы: тест утверждает точное тело запроса.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ViewsRepository

    private var listFails = false
    private var contentStatus = 200
    private var contentBody = CONTENT

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/views/") ->
                        if (listFails) MockResponse(code = 500) else ok(VIEWS)
                    path.endsWith("/views/content") ->
                        if (contentStatus == 200) ok(contentBody) else MockResponse(code = contentStatus)
                    path.endsWith("/views/tools/call") -> ok(TOOL_RESULT)
                    else -> MockResponse(code = 404)
                }
            }
        }
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
        Dispatchers.resetMain()
        server.close()
    }

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private fun requests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    private fun viewModel() = ViewsViewModel(
        repository,
        SavedStateHandle(mapOf("agentId" to "a1", "agentName" to "Луна")),
    )

    /** ViewModel с уже загруженным списком: открывать можно только строку из него. */
    private suspend fun loadedViewModel(): ViewsViewModel {
        val vm = viewModel()
        vm.state.first { !it.loading }
        return vm
    }

    @Test
    fun `load fills the list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel()

        val state = vm.state.first { !it.loading }

        assertNull(state.error)
        assertEquals(listOf("c1", "c2"), state.views.map { it.connectionId })
        assertEquals(listOf("Weather", "persist-memory"), state.views.map { it.title })
        assertEquals("a1", vm.agentId)
        assertEquals("Луна", vm.agentName)
    }

    @Test
    fun `load error is shown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        listFails = true
        val vm = viewModel()

        val state = vm.state.first { !it.loading }

        assertNotNull(state.error)
        assertEquals(emptyList<AgentView>(), state.views)
    }

    @Test
    fun `open loads the page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = loadedViewModel()

        vm.open(VIEW)

        val opened = vm.state.first { it.opened?.content != null }.opened!!
        assertEquals(VIEW, opened.view)
        assertEquals(HTML, opened.content)
        assertEquals(0, opened.revision)
        assertFalse(opened.loading)
        assertNull(opened.error)
        assertNull(opened.csp)

        val request = requests().first { it.url.encodedPath.endsWith("/views/content") }
        assertEquals("c1", request.url.queryParameter("connectionId"))
        assertEquals("ui://s/weather", request.url.queryParameter("uri"))
    }

    @Test
    fun `open carries the granted csp`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        contentBody = CONTENT_WITH_CSP
        val vm = loadedViewModel()

        vm.open(VIEW)

        val opened = vm.state.first { it.opened?.content != null }.opened!!
        assertEquals(
            ViewExternalDomains(
                resourceDomains = listOf("https://esm.sh"),
                connectDomains = listOf("https://api.example.com"),
            ),
            opened.csp,
        )
    }

    @Test
    fun `open error is shown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        contentStatus = 404
        val vm = loadedViewModel()

        vm.open(VIEW)

        val opened = vm.state.first { it.opened?.error != null }.opened!!
        assertFalse(opened.loading)
        assertNull(opened.content)
        assertEquals(0, opened.revision)
    }

    @Test
    fun `reload increments the revision even for identical html`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = loadedViewModel()
        vm.open(VIEW)
        val first = vm.state.first { it.opened?.content != null }.opened!!

        vm.reload()

        val reloaded = vm.state.first { it.opened?.revision == 1 }.opened!!
        assertEquals(first.content, reloaded.content)
        assertEquals(HTML, reloaded.content)
        assertFalse(reloaded.loading)
        assertEquals(2, requests().count { it.url.encodedPath.endsWith("/views/content") })
    }

    @Test
    fun `close clears the opened view`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = loadedViewModel()
        vm.open(VIEW)
        vm.state.first { it.opened?.content != null }

        vm.close()

        assertNull(vm.state.value.opened)
        assertEquals(2, vm.state.value.views.size)
    }

    @Test
    fun `callTool uses the opened view connection and not the page argument`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = loadedViewModel()
        vm.open(VIEW)
        vm.state.first { it.opened?.content != null }

        vm.callTool(
            VIEW,
            "save_memory",
            buildJsonObject {
                put("connectionId", "evil")
                put("agentId", "evil-agent")
                put("text", "hello")
            },
        )

        val call = requests().last { it.url.encodedPath.endsWith("/views/tools/call") }
        assertEquals("POST", call.method)
        // connectionId наверху — из привязки открытой вью; одноимённое поле в arguments остаётся
        // аргументом тула и связь не подменяет.
        assertEquals(
            """{"connectionId":"c1","name":"save_memory",""" +
                """"arguments":{"connectionId":"evil","agentId":"evil-agent","text":"hello"}}""",
            call.body?.utf8(),
        )
    }

    @Test
    fun `renderer gone shows an error and retry reloads`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = loadedViewModel()
        vm.open(VIEW)
        vm.state.first { it.opened?.content != null }

        vm.onRendererGone()

        val crashed = vm.state.value.opened!!
        assertEquals(uiText(R.string.views_crashed), crashed.error)

        vm.reload()

        val reloaded = vm.state.first { it.opened?.revision == 1 }.opened!!
        assertNull(reloaded.error)
        assertFalse(reloaded.loading)
        assertEquals(HTML, reloaded.content)
    }

    private companion object {
        val VIEW = AgentView(
            connectionId = "c1",
            connectorCode = "weather",
            connectionName = "Weather",
            uri = "ui://s/weather",
            tools = listOf("get_weather"),
        )

        const val HTML = "<html><body>sun</body></html>"

        const val VIEWS = """
            {"response":[
                {"connectionId":"c1","connectorCode":"weather","connectionName":"Weather",
                 "uri":"ui://s/weather","tools":["get_weather"]},
                {"connectionId":"c2","connectorCode":"persist-memory",
                 "uri":"ui://s/memory","tools":["remember","recall"]}]}
        """

        const val CONTENT = """
            {"response":{"uri":"ui://s/weather","mimeType":"text/html",
             "html":"<html><body>sun</body></html>"}}
        """

        const val CONTENT_WITH_CSP = """
            {"response":{"uri":"ui://s/weather","mimeType":"text/html",
             "html":"<html><body>sun</body></html>",
             "csp":{"resourceDomains":["https://esm.sh"],
                    "connectDomains":["https://api.example.com"]}}}
        """

        const val TOOL_RESULT = """
            {"response":{"content":[{"type":"text","text":"ok"}],"isError":false}}
        """
    }
}
