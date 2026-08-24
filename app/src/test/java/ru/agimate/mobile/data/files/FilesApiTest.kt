package ru.agimate.mobile.data.files

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.ApiJson
import java.time.Instant

/**
 * Слэш в конце пути значим, как и во всём этом API: `/manage/files/` отдаёт список, а без слэша
 * маршрута нет. Пустой фильтр не должен уезжать в запрос вовсе — сервер трактует его как
 * отсутствие, но полагаться на это незачем.
 */
class FilesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: FilesRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FilesApi::class.java)
        repository = FilesRepository(api)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun emptyPage() = MockResponse(
        code = 200,
        body = """{"response":{"content":[],"number":0,"size":30,"totalElements":0,"totalPages":0}}""",
    )

    @Test
    fun `listing keeps its trailing slash and drops empty filters`() = runTest {
        server.enqueue(emptyPage())
        repository.files(agentId = "", name = "   ", page = 0, size = 30)
        assertEquals("/control/manage/files/?page=0&size=30", server.takeRequest().target)
    }

    @Test
    fun `filters travel as query parameters`() = runTest {
        server.enqueue(emptyPage())
        repository.files(agentId = "a-1", name = "report", page = 2, size = 30)
        assertEquals(
            "/control/manage/files/?agentId=a-1&name=report&page=2&size=30",
            server.takeRequest().target,
        )
    }

    /**
     * Путь загрузки — общий файловый и **без слэша**: со слэшем это листинг. Вебчатовский
     * `POST /manage/webchat/files` удалён, и вернуться туда нельзя.
     */
    @Test
    fun `upload goes to the shared files endpoint without a trailing slash`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"response":{"id":"agf_1","name":"чек.png","type":"image",
                    "mime":"image/png","size":3,"url":"/files/agf_1?exp=1&sig=x"}}"""
                    .trimIndent().replace("\n", ""),
            )
        )

        val file = repository.upload("чек.png", "image/png", byteArrayOf(1, 2, 3))

        val request = server.takeRequest()
        assertEquals("/control/manage/files", request.target)
        assertEquals("POST", request.method)
        // Ключ ответа — `id`; `fileId` остался только внутри `parts` сообщения.
        assertEquals("agf_1", file.id)
    }

    @Test
    fun `upload names its parts file and origin`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"response":{"id":"agf_1"}}"""))
        repository.upload("чек.png", "image/png", byteArrayOf(1, 2, 3), origin = "chat")

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body.contains("""name="file""""))
        assertTrue(body.contains("""name="origin""""))
        // Метка уезжает голой: префикс `user:` приставляет сервер.
        assertTrue(body.contains("chat"))
    }

    @Test
    fun `deleting addresses one file`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"response":null}"""))
        repository.delete("agf_1")
        val request = server.takeRequest()
        assertEquals("/control/manage/files/agf_1", request.target)
        assertEquals("DELETE", request.method)
    }

    @Test
    fun `a row without a name and without a zone in dates still parses`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    {"response":{"content":[{"id":"agf_1","name":null,"type":"image",
                    "mime":"image/png","size":2048,"agentId":null,"origin":"tool",
                    "createdAt":"2026-08-15T16:44:13.310006",
                    "expiresAt":"2026-08-22T16:44:13.310006",
                    "url":"/files/agf_1?exp=1&sig=x"}],
                    "number":0,"size":30,"totalElements":1,"totalPages":1}}
                """.trimIndent().replace("\n", ""),
            )
        )

        val page = repository.files(agentId = null, name = null, page = 0)
        val file = page.items.single()

        assertNull(file.name)
        assertTrue(file.isImage)
        assertEquals(2048L, file.size)
        // Беззонное время сервер пишет в UTC — на этом стоит весь разбор дат.
        assertEquals(Instant.parse("2026-08-15T16:44:13.310006Z"), file.createdAt)
        assertTrue(page.isLast)
    }
}
