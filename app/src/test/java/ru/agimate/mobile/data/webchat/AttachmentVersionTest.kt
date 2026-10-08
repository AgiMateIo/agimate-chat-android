package ru.agimate.mobile.data.webchat

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
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson

/**
 * Агент переписывает файл, не меняя `agf_`: два сообщения с одним id показывают разные версии.
 * Свежая подпись обязана прийти на версию своего сообщения — иначе старое сообщение откроет новое
 * содержимое, и никто этого не заметит.
 */
class AttachmentVersionTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: WebchatRepository

    /** Страницы истории, от новых к старым. */
    private var pages: List<String> = emptyList()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val page = request.url.queryParameter("page")!!.toInt()
                return MockResponse(code = 200, body = pages[page])
            }
        }
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = WebchatRepository(retrofit.create(WebchatApi::class.java))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun page(number: Int, total: Int, vararg parts: String) = """
        {"response":{"content":[${parts.mapIndexed { i, p -> """{"id":"m-$number-$i","parts":[$p]}""" }.joinToString(",")}],
        "number":$number,"size":50,"totalElements":${total * 50},"totalPages":$total}}
    """

    private fun part(version: Int?, url: String) =
        """{"type":"file","fileId":"agf_1",${version?.let { "\"version\":$it," } ?: ""}"url":"$url"}"""

    @Test
    fun `fresh link is the one signed for the message's own version`() = runTest {
        pages = listOf(
            page(0, 2, part(2, "/files/agf_1?v=2&sig=new")),
            page(1, 2, part(1, "/files/agf_1?v=1&sig=old")),
        )

        assertEquals("/files/agf_1?v=1&sig=old", repository.freshAttachmentUrl("s-1", "agf_1", 1, pages = 2))
        assertEquals("/files/agf_1?v=2&sig=new", repository.freshAttachmentUrl("s-1", "agf_1", 2, pages = 2))
    }

    @Test
    fun `a message from before versions counts as version 1`() = runTest {
        pages = listOf(page(0, 1, part(null, "/files/agf_1?v=1&sig=legacy")))

        assertEquals("/files/agf_1?v=1&sig=legacy", repository.freshAttachmentUrl("s-1", "agf_1", 1, pages = 1))
    }

    @Test
    fun `search stops at the pages the feed has loaded`() = runTest {
        pages = listOf(
            page(0, 3, part(2, "/files/agf_1?v=2")),
            page(1, 3, part(2, "/files/agf_1?v=2")),
            page(2, 3, part(1, "/files/agf_1?v=1")),
        )

        assertNull(repository.freshAttachmentUrl("s-1", "agf_1", 1, pages = 2))
        assertEquals(2, server.requestCount)
    }
}
