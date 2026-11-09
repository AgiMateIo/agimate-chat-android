package ru.agimate.mobile.core.views

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ворота безопасности хоста вью: настройки WebView, одноразовая выдача обёртки и документа,
 * навигация наружу и отсутствие сетевых запросов со страницы.
 *
 * Проверки идут на живом WebView: `shouldInterceptRequest` и CSP работают только в нём, и
 * утверждать про них на моке — значит проверять мок.
 */
@RunWith(AndroidJUnit4::class)
class ViewHostSecurityTest {

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
    fun settingsAreLockedDown() {
        val view = newWebView()
        scenario.onActivity {
            configureViewWebView(view, Color.Black)
            val settings = view.settings
            assertTrue("JavaScript выключен — страница не заработает", settings.javaScriptEnabled)
            assertFalse("доступ к файлам открыт", settings.allowFileAccess)
            assertFalse("доступ к content-провайдерам открыт", settings.allowContentAccess)
            @Suppress("DEPRECATION")
            assertFalse("файловые URL видят файлы", settings.allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION")
            assertFalse("универсальный доступ открыт", settings.allowUniversalAccessFromFileURLs)
            assertFalse("DOM storage включён", settings.domStorageEnabled)
            @Suppress("DEPRECATION")
            assertFalse("база данных включена", settings.databaseEnabled)
            assertEquals(
                "смешанный контент не запрещён",
                WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                settings.mixedContentMode,
            )
            assertFalse("окна из JS открываются", settings.javaScriptCanOpenWindowsAutomatically)
            // Читается методом, а не свойством: геттер в WebSettings назван без префикса get.
            assertFalse("несколько окон разрешено", settings.supportMultipleWindows())
            assertTrue("медиа играет без жеста", settings.mediaPlaybackRequiresUserGesture)
            assertEquals("кеш разрешён", WebSettings.LOAD_NO_CACHE, settings.cacheMode)
            @Suppress("DEPRECATION")
            assertFalse("данные форм сохраняются", settings.saveFormData)
        }
    }

    @Test
    fun theWrapperIsServedOnceWithItsOwnCspHeaderAndThenForbidden() {
        val client = newClient()
        val view = newWebView()

        val first = client.shouldInterceptRequest(view, FakeRequest(ViewWrapper.URL))
        assertNotNull("обёртка не отдана", first)
        assertEquals(200, first!!.statusCode)
        assertEquals(WRAPPER_HTML, first.bodyText())
        assertEquals("кэширование обёртки не выключено", "no-store", first.header(CACHE_CONTROL))
        val csp = first.header(CONTENT_SECURITY_POLICY)
        assertNotNull("у обёртки нет CSP-заголовка", csp)
        assertTrue(
            "в политике обёртки нет разрешения на кадр вью: $csp",
            csp!!.contains("frame-src https://appassets.androidplatform.net"),
        )

        val second = client.shouldInterceptRequest(view, FakeRequest(ViewWrapper.URL))
        assertNotNull("повторный запрос обёртки не получил ответ", second)
        assertEquals("обёртка отдана дважды", 403, second!!.statusCode)
        assertEquals("тело 403 не пустое", "", second.bodyText())
    }

    @Test
    fun theViewIsServedOnceWithTheStrictCspHeaderAndThenForbidden() {
        val client = newClient()
        val view = newWebView()

        val first = client.shouldInterceptRequest(view, FakeRequest(ViewWrapper.VIEW_URL))
        assertNotNull("вью не отдана", first)
        assertEquals(200, first!!.statusCode)
        assertEquals(VIEW_HTML, first.bodyText())
        assertEquals("политика вью отличается от ViewCsp", ViewCsp.build(), first.header(CONTENT_SECURITY_POLICY))
        assertEquals("кэширование вью не выключено", "no-store", first.header(CACHE_CONTROL))

        val second = client.shouldInterceptRequest(view, FakeRequest(ViewWrapper.VIEW_URL))
        assertNotNull("повторный запрос вью не получил ответ", second)
        assertEquals("вью отдана дважды", 403, second!!.statusCode)

        // Флаги независимы: расход вью не расходует обёртку.
        val wrapper = client.shouldInterceptRequest(view, FakeRequest(ViewWrapper.URL))
        assertEquals("обёртка сломана повторной выдачей вью", 200, wrapper?.statusCode)
    }

    @Test
    fun localDataResourcesAreNotBlocked() {
        val client = newClient()
        val view = newWebView()

        assertNull(
            "data:-картинка заблокирована",
            client.shouldInterceptRequest(view, FakeRequest("data:image/png;base64,iVBORw0KGgo=")),
        )
        assertNull(
            "blob-ресурс заблокирован",
            client.shouldInterceptRequest(view, FakeRequest("blob:https://appassets.androidplatform.net/1")),
        )
        assertNull(
            "about:blank заблокирован",
            client.shouldInterceptRequest(view, FakeRequest("about:blank")),
        )
    }

    @Test
    fun navigationIsNeverPerformedInside() {
        val opened = CopyOnWriteArrayList<Uri>()
        val client = newClient(onExternalLink = opened::add)
        val view = newWebView()

        assertTrue(
            "https-переход не перехвачен",
            client.shouldOverrideUrlLoading(view, FakeRequest("https://example.com/page")),
        )
        assertEquals(listOf(Uri.parse("https://example.com/page")), opened.toList())

        assertTrue(
            "http-переход не заблокирован",
            client.shouldOverrideUrlLoading(view, FakeRequest("http://example.com/page")),
        )
        assertTrue(
            "javascript:-переход не заблокирован",
            client.shouldOverrideUrlLoading(view, FakeRequest("javascript:alert(1)")),
        )
        assertEquals("не-https адрес ушёл во внешний обработчик", 1, opened.size)

        assertFalse(
            "перехват навигации ломает загрузку самой обёртки",
            client.shouldOverrideUrlLoading(view, FakeRequest(ViewWrapper.URL)),
        )
    }

    @Test
    fun networkAccessFromThePageSendsNothing() {
        val server = MockWebServer()
        server.start()
        try {
            val attempted = CountDownLatch(1)
            startHost(
                content = networkFixture(server.url("/").toString()),
                onCallTool = { name, _ ->
                    if (name == NETWORK_REPORT) attempted.countDown()
                    buildJsonObject {}
                },
            )

            assertTrue(
                "страница не дождалась попыток выйти в сеть",
                attempted.await(15, TimeUnit.SECONDS),
            )
            // Запросы, которые не закрыл CSP, успевают дойти до сокета за это время.
            Thread.sleep(500)
            assertEquals("страница сходила в сеть, хотя не должна", 0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun aLargePageStillInitializes() {
        val reported = AtomicReference<JsonObject?>(null)
        val initialized = CountDownLatch(1)
        startHost(
            content = largePage(FILLER_SIZE),
            onCallTool = { name, arguments ->
                if (name == LARGE_REPORT) {
                    reported.set(arguments)
                    initialized.countDown()
                }
                buildJsonObject {}
            },
        )

        assertTrue(
            "страница на ${FILLER_SIZE / (1024 * 1024)} МБ не инициализировалась",
            initialized.await(60, TimeUnit.SECONDS),
        )
        val length = (reported.get()?.get("length") as? JsonPrimitive)?.content
        assertEquals("документ доехал до вью обрезанным", FILLER_SIZE.toString(), length)
    }

    private fun newClient(
        onExternalLink: (Uri) -> Unit = {},
        onRendererGone: () -> Unit = {},
    ): ViewWebViewClient = ViewWebViewClient(
        wrapper = { WRAPPER_HTML },
        view = { VIEW_HTML },
        onExternalLink = onExternalLink,
        onRendererGone = onRendererGone,
    )

    private fun newWebView(): WebView {
        lateinit var view: WebView
        scenario.onActivity { activity ->
            view = WebView(activity).also(webViews::add)
        }
        return view
    }

    private fun startHost(
        content: String,
        onCallTool: suspend (String, JsonObject) -> JsonObject = { _, _ -> buildJsonObject {} },
    ) {
        scenario.onActivity { activity ->
            val view = WebView(activity).also(webViews::add)
            val host = ViewHostController(
                webView = view,
                hostHtml = ViewWrapper.hostHtml(activity),
                content = content,
                hostContext = TEST_HOST,
                background = Color.Black,
                toolErrorText = { TOOL_ERROR },
                onCallTool = onCallTool,
                onOpenLink = { null },
                onRendererGone = {},
                scope = scope,
            )
            controller = host
            activity.setContentView(view)
            host.start()
        }
    }

    /**
     * Страница пробует все три пути, которыми WebView ходит в сеть: подресурс, `fetch` и
     * WebSocket. Сигнал уходит с паузой — за неё незаблокированный запрос успевает дойти до
     * сокета MockWebServer.
     */
    private fun networkFixture(base: String): String {
        val image = base + "img"
        val fetch = base + "fetch"
        val socket = base.replaceFirst("http://", "ws://") + "ws"
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>network</title></head>
            <body><script>
              (function () {
                try {
                  var image = new Image();
                  image.src = "$image";
                  document.body.appendChild(image);
                } catch (error) {}
                try {
                  fetch("$fetch").catch(function () {});
                } catch (error) {}
                try {
                  var socket = new WebSocket("$socket");
                  socket.onerror = function () {};
                } catch (error) {}
                setTimeout(function () {
                  parent.postMessage({
                    jsonrpc: "2.0",
                    id: 1,
                    method: "tools/call",
                    params: { name: "$NETWORK_REPORT", arguments: {} }
                  }, "*");
                }, 500);
              })();
            </script></body></html>
        """.trimIndent()
    }

    /**
     * Наполнитель — инертный `<script type="text/plain">`: браузер его не исполняет и не верстает,
     * поэтому 5 МБ текста проверяют доставку документа, а не скорость раскладки. Код страницы
     * стоит после наполнителя: если документ обрежется, код не выполнится и отчёта не будет.
     */
    private fun largePage(size: Int): String {
        val filler = "x".repeat(size)
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>large</title></head>
            <body>
            <script type="text/plain" id="filler">$filler</script>
            <script>
              window.addEventListener("message", function (event) {
                var data = event.data;
                if (!data || data.jsonrpc !== "2.0") return;
                if (data.id === 1 && data.result) {
                  parent.postMessage({
                    jsonrpc: "2.0",
                    id: 2,
                    method: "tools/call",
                    params: {
                      name: "$LARGE_REPORT",
                      arguments: { length: document.getElementById("filler").textContent.length }
                    }
                  }, "*");
                }
              });
              parent.postMessage({ jsonrpc: "2.0", id: 1, method: "ui/initialize", params: {} }, "*");
            </script>
            </body></html>
        """.trimIndent()
    }

    private fun WebResourceResponse.bodyText(): String =
        data.bufferedReader().use { it.readText() }

    private fun WebResourceResponse.header(name: String): String? =
        responseHeaders?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private class FakeRequest(
        private val rawUrl: String,
        private val httpMethod: String = "GET",
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(rawUrl)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = httpMethod
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private companion object {
        const val WRAPPER_HTML = "<!doctype html><html><body>wrapper</body></html>"
        const val VIEW_HTML = "<!doctype html><html><body>view</body></html>"
        const val CACHE_CONTROL = "Cache-Control"
        const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
        const val NETWORK_REPORT = "test/network"
        const val LARGE_REPORT = "test/large"
        const val TOOL_ERROR = "tool failed"
        const val FILLER_SIZE = 5 * 1024 * 1024

        val TEST_HOST = ViewHostContext(theme = "dark", locale = "ru", variables = emptyMap())
    }
}
