package ru.agimate.mobile.core.views

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ворота выполнимости Task 6: настоящий WebView, доверенная обёртка из assets и мост.
 *
 * Вью отдаёт тестовый `shouldInterceptRequest` — в приложении это делает `ViewWebViewClient`.
 * Если WebMessageListener не принимает origin `appassets`, дальше идти нельзя.
 */
@RunWith(AndroidJUnit4::class)
class ViewBridgeInstrumentedTest {

    private lateinit var scenario: ActivityScenario<ViewTestActivity>

    @Volatile
    private var bridge: ViewBridge? = null

    @Volatile
    private var webView: WebView? = null

    @Before
    fun launchActivity() {
        scenario = ActivityScenario.launch(ViewTestActivity::class.java)
    }

    @After
    fun destroyWebView() {
        runCatching {
            scenario.onActivity {
                bridge?.release()
                webView?.stopLoading()
                webView?.destroy()
            }
        }
        webView = null
        bridge = null
        scenario.close()
    }

    @Test
    fun bridgeRoundTripsARequestWithIdZero() {
        val hello = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val received = AtomicReference<JsonObject?>(null)

        startHost(
            onHello = { hello.countDown() },
            onRpc = { message ->
                when (message.string("method")) {
                    // id 0 — валидный id: ответ обязан вернуться с ним же, а не потеряться.
                    "ping" -> bridge?.send(
                        ViewProtocol.resultReply(message.getValue("id"), buildJsonObject { put("ok", true) }),
                    )
                    "test/reply" -> {
                        received.set(message)
                        reply.countDown()
                    }
                }
            },
        )

        assertTrue("hello не дошёл", hello.await(5, TimeUnit.SECONDS))
        assertTrue("ответ не вернулся в фикстуру", reply.await(5, TimeUnit.SECONDS))

        val envelope = received.get()
        assertEquals("test/reply", envelope?.string("method"))
        assertEquals(JsonPrimitive(0), envelope?.get("id"))
        val result = envelope?.getValue("params")?.jsonObject?.get("result")
        assertEquals(buildJsonObject { put("ok", true) }, result)
    }

    @Test
    fun bridgeRoundTripsThroughTheJavascriptInterfaceToo() {
        val hello = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val received = AtomicReference<JsonObject?>(null)

        startHost(
            forceInterface = true,
            onHello = { hello.countDown() },
            onRpc = { message ->
                when (message.string("method")) {
                    "ping" -> bridge?.send(
                        ViewProtocol.resultReply(message.getValue("id"), buildJsonObject { put("ok", true) }),
                    )
                    "test/reply" -> {
                        received.set(message)
                        reply.countDown()
                    }
                }
            },
        )

        assertEquals(ViewBridgeMode.JS_INTERFACE, bridge?.mode)
        assertTrue("hello не дошёл", hello.await(5, TimeUnit.SECONDS))
        assertTrue("ответ не вернулся в фикстуру", reply.await(5, TimeUnit.SECONDS))
        assertEquals(JsonPrimitive(0), received.get()?.get("id"))
    }

    @Test
    fun helloCarriesTheToken() {
        val hello = CountDownLatch(1)

        startHost(onHello = { hello.countDown() }, onRpc = {})

        // Мост зовёт onHello только после сверки токена, поэтому дошедший hello и есть
        // доказательство, что обёртка прислала правильный токен.
        assertTrue("hello не дошёл", hello.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aMessageWithAWrongTokenIsDropped() {
        val hello = CountDownLatch(1)
        val ping = CountDownLatch(1)
        val unexpected = CountDownLatch(1)

        startHost(
            onHello = { hello.countDown() },
            onRpc = { message ->
                // Штатный ping фикстуры доказывает, что канал жив; всё остальное — уже утечка.
                if (message.string("method") == "ping") ping.countDown() else unexpected.countDown()
            },
        )
        assertTrue("hello не дошёл", hello.await(5, TimeUnit.SECONDS))
        assertTrue("штатный ping не дошёл", ping.await(5, TimeUnit.SECONDS))

        postToBridge("""{"type":"rpc","token":"wrong","message":{}}""")
        assertFalse(
            "сообщение с чужим токеном дошло до onRpc",
            unexpected.await(500, TimeUnit.MILLISECONDS),
        )

        postToBridge("""{"type":"rpc","message":{}}""")
        assertFalse(
            "сообщение без токена дошло до onRpc",
            unexpected.await(500, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun theWrapperOriginIsAcceptedByTheWebMessageListener() {
        assertTrue(
            "WebView не поддерживает WebMessageListener — ворота выполнимости провалены",
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
        )

        val hello = CountDownLatch(1)
        startHost(onHello = { hello.countDown() }, onRpc = {})

        // Слушатель не доставляет сообщения с origin вне allowedOriginRules, так что дошедший
        // hello означает, что origin appassets принят.
        assertEquals(ViewBridgeMode.WEB_MESSAGE, bridge?.mode)
        assertTrue("слушатель не принял origin обёртки", hello.await(5, TimeUnit.SECONDS))
    }

    private fun startHost(
        forceInterface: Boolean = false,
        onHello: () -> Unit,
        onRpc: (JsonObject) -> Unit,
    ) {
        scenario.onActivity { activity ->
            val view = WebView(activity)
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    webView: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = when {
                    request.url.toString() == ViewWrapper.VIEW_URL -> response(200, "OK", FIXTURE)
                    // `loadDataWithBaseURL` внутри ходит за документом по `data:`; ответ на этот
                    // запрос подменяет обёртку, и её скрипт не выполняется. Локальные схемы
                    // WebView отдаёт сам.
                    request.url.scheme in LOCAL_SCHEMES -> null
                    else -> response(403, "Forbidden", "")
                }
            }
            val viewBridge = ViewBridge.create(
                token = TOKEN,
                forceInterface = forceInterface,
                onHello = onHello,
                onRpc = onRpc,
            )
            viewBridge.install(view)
            activity.setContentView(view)
            val wrapper = ViewWrapper.build(
                hostHtml = ViewWrapper.hostHtml(activity),
                viewUrl = ViewWrapper.VIEW_URL,
                token = TOKEN,
                background = BACKGROUND,
            )
            view.loadDataWithBaseURL(
                ViewWrapper.URL,
                wrapper,
                "text/html",
                "utf-8",
                null,
            )
            webView = view
            bridge = viewBridge
        }
    }

    private fun postToBridge(envelope: String) {
        val call = when (bridge?.mode) {
            ViewBridgeMode.JS_INTERFACE -> "window.AgimateHost.post"
            else -> "window.AgimateHost.postMessage"
        }
        scenario.onActivity {
            webView?.evaluateJavascript("$call(${JSONObject.quote(envelope)})", null)
        }
    }

    private fun response(status: Int, reason: String, body: String): WebResourceResponse =
        WebResourceResponse(
            "text/html",
            "utf-8",
            status,
            reason,
            mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"),
            ByteArrayInputStream(body.toByteArray()),
        )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val TOKEN = "view-test-token"
        const val BACKGROUND = "#101010"
        val LOCAL_SCHEMES = setOf("data", "blob", "about")

        /**
         * Минимальная страница вью: шлёт ping с id 0 и, получив ответ, возвращает его нативному
         * хосту через мост — тест видит, что ответ дошёл именно до фикстуры.
         */
        val FIXTURE = """
            <!doctype html>
            <html><body><script>
              window.addEventListener("message", function (event) {
                var data = event.data;
                if (!data || data.jsonrpc !== "2.0" || !data.result) return;
                parent.postMessage({
                  jsonrpc: "2.0",
                  id: data.id,
                  method: "test/reply",
                  params: { result: data.result }
                }, "*");
              });
              parent.postMessage({ jsonrpc: "2.0", id: 0, method: "ping" }, "*");
            </script></body></html>
        """.trimIndent()
    }
}
