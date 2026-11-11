package ru.agimate.mobile.data.views

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.ui.text.uiText
import java.util.concurrent.TimeUnit

/**
 * Один загрузчик на две поверхности: карточка грузит вызов и страницу параллельно (дескриптор уже
 * есть), полный экран — последовательно (в маршруте только id). Классификация `404/403/502` одна:
 * от неё зависит, карточка останется, снимется или покажет ошибку с повтором.
 */
class ToolCallViewLoaderTest {

    private lateinit var server: MockWebServer
    private lateinit var loader: ToolCallViewLoader

    private var callStatus = 200
    private var callBody = READY_CALL
    private var contentStatus = 200
    private var contentBody = CONTENT

    private val descriptor = ToolCallDescriptor(
        toolCallId = "c2",
        name = "show_memory",
        connectionId = "k1",
        viewUri = "ui://v",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.contains("/tool-calls/") -> MockResponse(code = callStatus, body = callBody)
                    path.endsWith("/views/content") ->
                        MockResponse(code = contentStatus, body = contentBody)
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ViewsApi::class.java)
        loader = ToolCallViewLoader(ViewsRepository(api))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `descriptor load requests the call and the page`() = runTest {
        val result = loader.load("a1", "s1", descriptor)

        assertTrue("expected Ready, got $result", result is ToolCallView.Ready)
        result as ToolCallView.Ready
        assertEquals("show_memory", result.call.name)
        assertEquals("<html>view</html>", result.content.html)

        val paths = requests().map { it.url.encodedPath }
        assertEquals(2, paths.size)
        assertTrue(paths.any { it == "/control/manage/sessions/s1/tool-calls/c2" })
        assertTrue(paths.any { it == "/control/manage/agents/a1/views/content" })
    }

    @Test
    fun `call 404 and 403 are NoView`() = runTest {
        callStatus = 404
        assertEquals(ToolCallView.NoView, loader.load("a1", "s1", descriptor))

        callStatus = 403
        assertEquals(ToolCallView.NoView, loader.load("a1", "s1", descriptor))
    }

    @Test
    fun `a call without a view is NoView`() = runTest {
        callBody = """{"response":{"toolCallId":"c2","name":"get_memory","connectionId":"k1"}}"""

        assertEquals(ToolCallView.NoView, loader.load("a1", "s1", descriptor))
    }

    @Test
    fun `page 404 is PageGone and page 403 is NoView`() = runTest {
        contentStatus = 404
        assertEquals(ToolCallView.PageGone, loader.load("a1", "s1", descriptor))

        contentStatus = 403
        assertEquals(ToolCallView.NoView, loader.load("a1", "s1", descriptor))
    }

    @Test
    fun `page 502 is PageFailed with the server text`() = runTest {
        contentStatus = 502
        contentBody = """{"error":{"message":"server did not answer"}}"""

        val result = loader.load("a1", "s1", descriptor)

        result as ToolCallView.PageFailed
        assertEquals(uiText("server did not answer"), result.message)
    }

    @Test
    fun `call 502 is CallFailed with the server text`() = runTest {
        callStatus = 502
        callBody = """{"error":{"message":"journal is down"}}"""

        val result = loader.load("a1", "s1", descriptor)

        result as ToolCallView.CallFailed
        assertEquals(uiText("journal is down"), result.message)
    }

    @Test
    fun `a running call keeps a null result`() = runTest {
        callBody = """{"response":{"toolCallId":"c2","name":"show_memory","connectionId":"k1",
            "viewUri":"ui://v","input":{}}}"""

        val result = loader.load("a1", "s1", descriptor) as ToolCallView.Ready

        assertNull(result.call.result)
    }

    @Test
    fun `the sequential overload fetches the call before the page`() = runTest {
        val result = loader.load("a1", "s1", "c2")

        assertTrue("expected Ready, got $result", result is ToolCallView.Ready)
        val paths = requests().map { it.url.encodedPath }
        assertEquals(
            listOf(
                "/control/manage/sessions/s1/tool-calls/c2",
                "/control/manage/agents/a1/views/content",
            ),
            paths,
        )
    }

    @Test
    fun `refresh maps 404 to Gone, success to Updated and a server error to Failed`() = runTest {
        callStatus = 404
        assertEquals(ToolCallRefresh.Gone, loader.refreshCall("s1", "c2"))

        callStatus = 200
        val updated = loader.refreshCall("s1", "c2")
        assertTrue("expected Updated, got $updated", updated is ToolCallRefresh.Updated)

        callStatus = 500
        assertEquals(ToolCallRefresh.Failed, loader.refreshCall("s1", "c2"))
    }

    private fun requests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    private companion object {
        const val READY_CALL =
            """{"response":{"toolCallId":"c2","name":"show_memory","connectionId":"k1",
                "viewUri":"ui://v","input":{"q":"all"},
                "result":{"content":[{"type":"text","text":"ok"}],"isError":false}}}"""
        const val CONTENT =
            """{"response":{"uri":"ui://v","mimeType":"text/html","html":"<html>view</html>"}}"""
    }
}
