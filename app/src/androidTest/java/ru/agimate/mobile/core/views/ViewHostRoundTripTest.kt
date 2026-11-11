package ru.agimate.mobile.core.views

import android.net.Uri
import android.webkit.WebView
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.agimate.mobile.core.network.ApiException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Сквозной путь сообщений через настоящий WebView: страница в sandbox-iframe говорит с хостом
 * через обёртку, хост отвечает, ошибки тулов не разваливают канал.
 */
@RunWith(AndroidJUnit4::class)
class ViewHostRoundTripTest {

    private lateinit var scenario: ActivityScenario<ViewTestActivity>
    private lateinit var scope: CoroutineScope
    private var controller: ViewHostController? = null
    private val webViews = CopyOnWriteArrayList<WebView>()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ViewTestActivity::class.java)
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }

    @After
    fun tearDown() {
        scenario.onActivity {
            controller?.release()
            controller = null
            webViews.forEach {
                it.stopLoading()
                it.destroy()
            }
            webViews.clear()
        }
        scope.cancel()
        scenario.close()
    }

    @Test
    fun initializeAndToolsCallGoThrough() {
        val calls = CopyOnWriteArrayList<String>()
        val report = AtomicReference<JsonObject?>(null)
        val reportLatch = CountDownLatch(1)

        startHost(
            content = ROUND_TRIP_FIXTURE,
            onCallTool = { name, arguments ->
                calls += name
                when (name) {
                    "get_weather" -> buildJsonObject {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", "rain")
                            }
                        }
                        put("isError", true)
                    }
                    "report" -> {
                        report.set(arguments)
                        reportLatch.countDown()
                        buildJsonObject {}
                    }
                    else -> buildJsonObject {}
                }
            },
        )

        assertTrue("результат тула не вернулся на страницу", reportLatch.await(15, TimeUnit.SECONDS))
        assertEquals(listOf("get_weather", "report"), calls.toList())

        val result = report.get()?.get("result")?.jsonObject
        assertNotNull("в отчёт не попал result вызова", result)
        assertEquals("isError потерялся по дороге", JsonPrimitive(true), result?.get("isError"))
    }

    @Test
    fun openLinkGoesToTheHost() {
        val opened = AtomicReference<Uri?>(null)
        val linkLatch = CountDownLatch(1)
        val replyLatch = CountDownLatch(1)

        startHost(
            content = OPEN_LINK_FIXTURE,
            onCallTool = { name, _ ->
                if (name == "report") replyLatch.countDown()
                buildJsonObject {}
            },
            onOpenLink = { uri ->
                opened.set(uri)
                linkLatch.countDown()
                null
            },
        )

        assertTrue("ссылка не дошла до хоста", linkLatch.await(15, TimeUnit.SECONDS))
        assertEquals(Uri.parse("https://example.com/page"), opened.get())
        assertTrue("ответ на ui/open-link не вернулся странице", replyLatch.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun aHostileToolErrorBecomesAJsonRpcError() {
        val report = AtomicReference<JsonObject?>(null)
        val reportLatch = CountDownLatch(1)

        startHost(
            content = ERROR_FIXTURE,
            onCallTool = { name, arguments ->
                if (name == "get_weather") throw ApiException.Offline(IOException("boom"))
                report.set(arguments)
                reportLatch.countDown()
                buildJsonObject {}
            },
            toolErrorText = { TOOL_ERROR },
        )

        assertTrue("ошибка тула не вернулась странице", reportLatch.await(15, TimeUnit.SECONDS))
        val error = report.get()
        assertEquals("код ошибки не -32603", "-32603", (error?.get("code") as? JsonPrimitive)?.content)
        assertEquals("серверный текст ошибки потерялся", TOOL_ERROR, (error?.get("message") as? JsonPrimitive)?.content)
    }

    private fun startHost(
        content: String,
        onCallTool: suspend (String, JsonObject) -> JsonObject,
        onOpenLink: (Uri) -> String? = { null },
        toolErrorText: (Throwable) -> String = { TOOL_ERROR },
        toolCall: ViewCallData? = null,
    ) {
        scenario.onActivity { activity ->
            val view = WebView(activity).also(webViews::add)
            val host = ViewHostController(
                webView = view,
                hostHtml = ViewWrapper.hostHtml(activity),
                content = content,
                hostContext = TEST_HOST,
                background = Color.Black,
                toolErrorText = toolErrorText,
                onCallTool = onCallTool,
                onOpenLink = onOpenLink,
                onRendererGone = {},
                scope = scope,
                toolCall = toolCall,
            )
            controller = host
            activity.setContentView(view)
            host.start()
        }
    }

    /**
     * Inline-данные: страница присылает `initialized`, хост выдерживает паузу и шлёт `tool-input`,
     * затем `tool-result`. Обновление результата досылает только второй, без повторного входа.
     */
    @Test
    fun inlineDataGoesAfterInitializedAndResultUpdatesAlone() {
        val reports = CopyOnWriteArrayList<List<String>>()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val input = buildJsonObject { put("city", "Paris") }
        val result = buildJsonObject {
            putJsonObject("structuredContent") { put("temp", 21) }
            put("isError", false)
        }

        startHost(
            content = INLINE_FIXTURE,
            onCallTool = { name, arguments ->
                if (name == INLINE_REPORT) {
                    val seen = (arguments["seen"] as JsonArray)
                        .map { (it as JsonPrimitive).content }
                    reports += seen
                    if (seen.count { it.startsWith("input:") } == 1 &&
                        seen.count { it.startsWith("result:") } == 1
                    ) {
                        first.countDown()
                    }
                    if (seen.count { it.startsWith("result:") } == 2) second.countDown()
                }
                buildJsonObject {}
            },
            toolCall = ViewCallData(input = input, result = result),
        )

        assertTrue("tool-input и tool-result не дошли", first.await(15, TimeUnit.SECONDS))
        // Фикстура отчитывается после каждой нотификации: первый отчёт — только вход, второй —
        // вход и результат. Порядок проверяется по отчёту, где есть оба.
        val firstReport = reports.first {
            it.count { seen -> seen.startsWith("input:") } == 1 &&
                it.count { seen -> seen.startsWith("result:") } == 1
        }
        assertEquals("ожидались ровно вход и результат", 2, firstReport.size)
        assertTrue(
            "tool-input должен идти первым: $firstReport",
            firstReport[0].startsWith("input:") && firstReport[0].contains("\"city\":\"Paris\""),
        )
        assertTrue(
            "tool-result должен идти вторым: $firstReport",
            firstReport[1].startsWith("result:") && firstReport[1].contains("\"temp\":21"),
        )

        val updated = buildJsonObject {
            putJsonObject("structuredContent") { put("temp", 22) }
            put("isError", false)
        }
        scenario.onActivity { controller?.updateToolCall(ViewCallData(input, updated)) }

        assertTrue("обновление результата не дошло", second.await(15, TimeUnit.SECONDS))
        val latest = reports.last()
        assertEquals("tool-input повторился", 1, latest.count { it.startsWith("input:") })
        assertEquals("tool-result не обновился", 2, latest.count { it.startsWith("result:") })
        assertTrue(latest.last().contains("22"))
    }

    private companion object {
        const val TOOL_ERROR = "tool failed"
        const val INLINE_REPORT = "test/inline"
        val TEST_HOST = ViewHostContext(theme = "light", locale = "en", variables = emptyMap())

        /**
         * Страница инициализируется, просит погоду, а полученный `result` целиком отправляет
         * вторым вызовом — так тест видит, что до страницы доехал именно ответ хоста.
         */
        val ROUND_TRIP_FIXTURE = """
            <!doctype html>
            <html><body><script>
              window.addEventListener("message", function (event) {
                var data = event.data;
                if (!data || data.jsonrpc !== "2.0") return;
                if (data.id === 1 && data.result) {
                  parent.postMessage({
                    jsonrpc: "2.0",
                    id: 2,
                    method: "tools/call",
                    params: { name: "get_weather", arguments: { city: "Paris" } }
                  }, "*");
                } else if (data.id === 2 && data.result) {
                  parent.postMessage({
                    jsonrpc: "2.0",
                    id: 3,
                    method: "tools/call",
                    params: { name: "report", arguments: { result: data.result } }
                  }, "*");
                }
              });
              parent.postMessage({ jsonrpc: "2.0", id: 1, method: "ui/initialize", params: {} }, "*");
            </script></body></html>
        """.trimIndent()

        /** Просит открыть ссылку и сообщает хосту, что ответ на запрос дошёл. */
        val OPEN_LINK_FIXTURE = """
            <!doctype html>
            <html><body><script>
              window.addEventListener("message", function (event) {
                var data = event.data;
                if (!data || data.jsonrpc !== "2.0") return;
                if (data.id === 1 && data.result) {
                  parent.postMessage({
                    jsonrpc: "2.0",
                    id: 2,
                    method: "tools/call",
                    params: { name: "report", arguments: {} }
                  }, "*");
                }
              });
              parent.postMessage({
                jsonrpc: "2.0",
                id: 1,
                method: "ui/open-link",
                params: { url: "https://example.com/page" }
              }, "*");
            </script></body></html>
        """.trimIndent()

        /** Слушает inline-данные хоста и пересылает их копилку обратно через `report`. */
        val INLINE_FIXTURE = """
            <!doctype html>
            <html><body><script>
              var seen = [];
              function report() {
                parent.postMessage({
                  jsonrpc: "2.0", id: 1, method: "tools/call",
                  params: { name: "$INLINE_REPORT", arguments: { seen: seen.slice() } }
                }, "*");
              }
              window.addEventListener("message", function (event) {
                var data = event.data;
                if (!data || data.jsonrpc !== "2.0") return;
                if (data.method === "ui/notifications/tool-input") {
                  seen.push("input:" + JSON.stringify(data.params.arguments));
                  report();
                } else if (data.method === "ui/notifications/tool-result") {
                  seen.push("result:" + JSON.stringify(data.params));
                  report();
                }
              });
              setTimeout(function () {
                parent.postMessage({
                  jsonrpc: "2.0", method: "ui/notifications/initialized", params: {}
                }, "*");
              }, 50);
            </script></body></html>
        """.trimIndent()

        /** Вызывает тул и возвращает хосту код и текст ошибки, которые увидела страница. */
        val ERROR_FIXTURE = """
            <!doctype html>
            <html><body><script>
              window.addEventListener("message", function (event) {
                var data = event.data;
                if (!data || data.jsonrpc !== "2.0") return;
                if (data.id === 1 && data.error) {
                  parent.postMessage({
                    jsonrpc: "2.0",
                    id: 2,
                    method: "tools/call",
                    params: {
                      name: "report",
                      arguments: { code: data.error.code, message: data.error.message }
                    }
                  }, "*");
                }
              });
              parent.postMessage({
                jsonrpc: "2.0",
                id: 1,
                method: "tools/call",
                params: { name: "get_weather", arguments: {} }
              }, "*");
            </script></body></html>
        """.trimIndent()
    }
}
