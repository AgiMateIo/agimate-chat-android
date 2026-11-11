package ru.agimate.mobile.feature.views

import androidx.lifecycle.SavedStateHandle
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.realtime.RealtimeEvent
import ru.agimate.mobile.core.realtime.RealtimeEventsSource
import ru.agimate.mobile.core.realtime.SessionToolCallPayloadDto
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.views.AgentViewContent
import ru.agimate.mobile.data.views.SessionToolCall
import ru.agimate.mobile.data.views.ToolCallDescriptor
import ru.agimate.mobile.data.views.ToolCallRefresh
import ru.agimate.mobile.data.views.ToolCallView
import ru.agimate.mobile.data.views.ToolCallViewSource
import ru.agimate.mobile.data.views.ViewsApi
import ru.agimate.mobile.data.views.ViewsRepository

/**
 * Полный экран вью: тот же загрузчик, что у карточки, но маршрут несёт только id — вызов и
 * страница грузятся последовательно. Нет вью — экран закрывается; ошибка страницы — текст и повтор.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var views: ViewsRepository
    private val content = AgentViewContent(html = "<html>view</html>", mimeType = "text/html")
    private val call = SessionToolCall(
        toolCallId = "c2",
        name = "show_memory",
        connectionId = "k1",
        viewUri = "ui://v",
        input = buildJsonObject { put("q", "all") },
        result = buildJsonObject { put("isError", false) },
    )

    private class FakeSource : ToolCallViewSource {
        val loads = mutableListOf<String>()
        val refreshes = mutableListOf<String>()
        var next = CompletableDeferred<ToolCallView>()
        var nextRefresh = CompletableDeferred<ToolCallRefresh>()

        override suspend fun load(
            agentId: String,
            sessionId: String,
            descriptor: ToolCallDescriptor,
        ): ToolCallView = error("the full screen uses the id overload")

        override suspend fun load(
            agentId: String,
            sessionId: String,
            toolCallId: String,
        ): ToolCallView {
            loads += toolCallId
            return next.await()
        }

        override suspend fun refreshCall(
            sessionId: String,
            toolCallId: String,
        ): ToolCallRefresh {
            refreshes += toolCallId
            return nextRefresh.await()
        }
    }

    private class FakeRealtime : RealtimeEventsSource {
        override val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        views = ViewsRepository(
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

    private fun viewModel(
        source: FakeSource,
        realtime: FakeRealtime = FakeRealtime(),
    ) = AgentViewViewModel(
        source,
        views,
        realtime,
        SavedStateHandle(
            mapOf("agentId" to "a1", "sessionId" to "s1", "toolCallId" to "c2"),
        ),
    )

    @Test
    fun `a ready view fills content and call`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeSource()
        val vm = viewModel(source)
        source.next.complete(ToolCallView.Ready(call, content))

        val state = vm.state.first { !it.loading }

        assertEquals(content, state.content)
        assertEquals(call, state.call)
        assertFalse(state.close)
        assertEquals(listOf("c2"), source.loads)
    }

    @Test
    fun `a signal updates a mounted view result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeSource()
        val realtime = FakeRealtime()
        val vm = viewModel(source, realtime)
        source.next.complete(ToolCallView.Ready(call.copy(result = null), content))
        vm.state.first { it.content != null }
        advanceUntilIdle()

        source.nextRefresh.complete(ToolCallRefresh.Updated(call))
        realtime.events.emit(
            RealtimeEvent.ToolCall(
                SessionToolCallPayloadDto(sessionId = "s1", toolCallIds = listOf("c2")),
            ),
        )
        advanceUntilIdle()

        assertEquals(call.result, vm.state.value.call?.result)
        assertEquals(listOf("c2"), source.refreshes)
    }

    @Test
    fun `a signal for another session is ignored`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeSource()
        val realtime = FakeRealtime()
        val vm = viewModel(source, realtime)
        source.next.complete(ToolCallView.Ready(call, content))
        vm.state.first { it.content != null }
        advanceUntilIdle()

        realtime.events.emit(
            RealtimeEvent.ToolCall(
                SessionToolCallPayloadDto(sessionId = "other", toolCallIds = listOf("c2")),
            ),
        )
        advanceUntilIdle()

        assertTrue(source.refreshes.isEmpty())
    }

    @Test
    fun `no view and a gone page close the screen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val noView = FakeSource()
        val first = viewModel(noView)
        noView.next.complete(ToolCallView.NoView)
        assertTrue(first.state.first { it.close }.close)

        val gone = FakeSource()
        val second = viewModel(gone)
        gone.next.complete(ToolCallView.PageGone)
        assertTrue(second.state.first { it.close }.close)
    }

    @Test
    fun `a page failure shows the text and retry reloads`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeSource()
        val vm = viewModel(source)
        source.next.complete(ToolCallView.PageFailed(uiText("bad page")))
        val failed = vm.state.first { !it.loading && it.error != null }
        assertEquals(uiText("bad page"), failed.error)

        source.next = CompletableDeferred()
        source.next.complete(ToolCallView.Ready(call, content))
        vm.retry()
        val ready = vm.state.first { it.content != null }
        assertNotNull(ready.content)
        assertEquals(2, source.loads.size)
    }

    @Test
    fun `callTool uses the agent and connection from native state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"response":{"content":[{"type":"text","text":"ok"}],"isError":false}}""",
            ),
        )
        val source = FakeSource()
        val vm = viewModel(source)
        source.next.complete(ToolCallView.Ready(call, content))
        vm.state.first { !it.loading }

        val result = vm.callTool("show_memory", buildJsonObject { put("query", "all") })

        val request = server.takeRequest()
        assertEquals("/control/manage/agents/a1/views/tools/call", request.target)
        assertTrue(request.body?.utf8().orEmpty().contains("\"connectionId\":\"k1\""))
        assertNotNull(result["isError"])
    }
}
