package ru.agimate.mobile.core.views

import android.webkit.WebView
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Раскладка обёртки: кадр вью обязан занимать вьюпорт хоста целиком.
 *
 * Проверка идёт на настоящем WebView, потому что дефект — в том, как он считает процентную высоту
 * корня документа; на моке такое не воспроизводится.
 */
@RunWith(AndroidJUnit4::class)
class ViewHostLayoutTest {

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
    fun iframeFillsTheHostViewport() {
        val loaded = CountDownLatch(1)

        scenario.onActivity { activity ->
            val view = WebView(activity).also(webViews::add)
            val host = ViewHostController(
                webView = view,
                hostHtml = ViewWrapper.hostHtml(activity),
                content = LOADED_FIXTURE,
                hostContext = TEST_HOST,
                background = Color.Black,
                toolErrorText = { TOOL_ERROR },
                onCallTool = { name, _ ->
                    if (name == LOADED_REPORT) loaded.countDown()
                    buildJsonObject {}
                },
                onOpenLink = { null },
                onRendererGone = {},
                scope = scope,
            )
            controller = host
            activity.setContentView(view)
            host.start()
        }

        assertTrue("вью не загрузилась в кадр вовремя", loaded.await(15, TimeUnit.SECONDS))

        val height = iframeHeight()
        assertTrue(
            "кадр вью занимает $height css px вместо высоты вьюпорта — корень обёртки схлопнулся",
            height > 100f,
        )
    }

    /**
     * Меряет кадр изнутри обёртки. `evaluateJavascript` вызывается с UI-потока, а его ответ
     * приходит туда же — поэтому ожидание остаётся на тестовом потоке.
     *
     * Страница сообщает о загрузке раньше, чем WebView успевает разложиться: на медленном
     * устройстве `getBoundingClientRect` ещё нулевой. Поэтому не один замер, а опрос до срока:
     * проверяется раскладка, а не гонка с ней.
     */
    private fun iframeHeight(): Float {
        val deadline = System.currentTimeMillis() + LAYOUT_TIMEOUT_MS
        var last = -1f
        while (System.currentTimeMillis() < deadline) {
            last = measureIframeOnce()
            if (last > 100f) return last
            Thread.sleep(LAYOUT_POLL_MS)
        }
        return last
    }

    private fun measureIframeOnce(): Float {
        val measured = AtomicReference(-1f)
        val latch = CountDownLatch(1)

        scenario.onActivity {
            val view = webViews.single()
            view.evaluateJavascript(IFRAME_HEIGHT_JS) { raw ->
                // Ответ приходит JSON-строкой: значение String(...) лежит в кавычках.
                measured.set(raw?.removeSurrounding("\"")?.toFloatOrNull() ?: -1f)
                latch.countDown()
            }
        }

        assertTrue("высота кадра не вернулась из WebView", latch.await(15, TimeUnit.SECONDS))
        return measured.get()
    }

    private companion object {
        const val LOADED_REPORT = "test/loaded"
        const val TOOL_ERROR = "tool failed"
        const val LAYOUT_TIMEOUT_MS = 15_000L
        const val LAYOUT_POLL_MS = 100L

        val TEST_HOST = ViewHostContext(theme = "light", locale = "en", variables = emptyMap())

        /** Кадр сообщает хосту о загрузке — по этому сигналу тест знает, что документ разложен. */
        val LOADED_FIXTURE = """
            <!doctype html>
            <html><body><script>
              parent.postMessage({
                jsonrpc: "2.0",
                id: 1,
                method: "tools/call",
                params: { name: "$LOADED_REPORT", arguments: {} }
              }, "*");
            </script></body></html>
        """.trimIndent()

        const val IFRAME_HEIGHT_JS =
            "(() => { const f = document.querySelector('iframe'); " +
                "return f ? String(f.getBoundingClientRect().height) : 'null'; })()"
    }
}
