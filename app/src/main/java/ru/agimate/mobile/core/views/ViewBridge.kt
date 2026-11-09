package ru.agimate.mobile.core.views

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.json.JSONObject
import ru.agimate.mobile.core.network.ApiJson

/** Канал, которым обёртка говорит с нативом. */
enum class ViewBridgeMode {
    /** `WebMessageListener`: origin проверяет сам WebView. */
    WEB_MESSAGE,

    /** `addJavascriptInterface`: единственный путь там, где слушателя нет. */
    JS_INTERFACE,
}

/**
 * JSON-RPC канал между доверенной обёрткой и нативом.
 *
 * Токен — единственное, что отличает обёртку от чужого документа: WebView не даёт вью добраться
 * до обёртки, но если бы дал, без токена сообщение всё равно отбрасывается. Поэтому проверка
 * стоит до разбора типа: и `hello`, и `rpc` без верного токена не получают ответа.
 *
 * Входящие конверты — `{"type":"hello","token":…}` и `{"type":"rpc","token":…,"message":{…}}`,
 * исходящие — только `{"type":"rpc","message":{…}}`.
 */
class ViewBridge private constructor(
    val mode: ViewBridgeMode,
    val token: String,
    private val onHello: () -> Unit,
    private val onRpc: (JsonObject) -> Unit,
) {

    @Volatile
    private var webView: WebView? = null

    /** После [release] сообщения, уже поставленные в очередь, не должны доходить до колбэков. */
    @Volatile
    private var released = false

    private var listener: WebViewCompat.WebMessageListener? = null
    private var replyProxy: JavaScriptReplyProxy? = null
    private var jsBridge: JsBridge? = null

    /**
     * Подключает канал к WebView. Вызывается с UI-потока и до загрузки обёртки, иначе hello из
     * неё потеряется.
     */
    @SuppressLint("AddJavascriptInterface")
    fun install(webView: WebView) {
        this.webView = webView
        when (mode) {
            ViewBridgeMode.WEB_MESSAGE -> {
                // В webkit 1.18 replyProxy — последний аргумент: порядок отличается от старых
                // версий библиотеки, и перепутать его значит молча остаться без ответов.
                val listener = WebViewCompat.WebMessageListener { _, message, _, isMainFrame, proxy ->
                    if (isMainFrame) {
                        replyProxy = proxy
                        message.data?.let(::handle)
                    }
                }
                WebViewCompat.addWebMessageListener(
                    webView,
                    HOST_NAME,
                    setOf(ViewWrapper.ORIGIN),
                    listener,
                )
                this.listener = listener
            }
            ViewBridgeMode.JS_INTERFACE -> {
                val bridge = JsBridge()
                webView.addJavascriptInterface(bridge, HOST_NAME)
                jsBridge = bridge
            }
        }
    }

    /**
     * Отправляет странице сообщение, оборачивая его в конверт моста.
     *
     * Ответ возможен только после первого сообщения страницы: до него `replyProxy` в режиме
     * WebMessage ещё не выдан. Для хоста это не ограничение — он отвечает на уже полученное.
     */
    fun send(message: JsonObject) {
        val envelope = buildJsonObject {
            put("type", "rpc")
            put("message", message)
        }
        when (mode) {
            ViewBridgeMode.WEB_MESSAGE -> post {
                replyProxy?.postMessage(envelope.toString())
            }
            ViewBridgeMode.JS_INTERFACE -> post {
                val script = "window.__agimateHostReceive(${JSONObject.quote(envelope.toString())})"
                webView?.evaluateJavascript(script, null)
            }
        }
    }

    /** Снимает канал с UI-потока. Повторный вызов безопасен: после первого ссылки на WebView нет. */
    fun release() {
        released = true
        val view = webView
        if (view != null) {
            when (mode) {
                ViewBridgeMode.WEB_MESSAGE -> if (listener != null) {
                    WebViewCompat.removeWebMessageListener(view, HOST_NAME)
                }
                ViewBridgeMode.JS_INTERFACE -> view.removeJavascriptInterface(HOST_NAME)
            }
        }
        webView = null
        listener = null
        replyProxy = null
        jsBridge = null
    }

    private fun post(block: () -> Unit) {
        webView?.post(block)
    }

    private fun handle(envelope: String) {
        if (released) return
        val parsed = runCatching { ApiJson.parseToJsonElement(envelope) }.getOrNull()
        val message = parsed as? JsonObject ?: return
        val incoming = (message["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (incoming != token) return
        when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
            "hello" -> onHello()
            "rpc" -> (message["message"] as? JsonObject)?.let(onRpc)
        }
    }

    /**
     * Запасной канал: метод зовётся на фоновом потоке WebView, поэтому разбор переносится в
     * очередь UI-потока — колбэки моста не должны требовать от вызывающего знания потоков.
     *
     * R8 держит этот метод правилом на `ViewBridge$JsBridge`: имя класса нужно только для того,
     * чтобы правило нашлось до сжатия, а странице видно имя метода — `post`. Иначе release-сборка
     * могла бы вырезать метод, и мост исчез бы только в релизе.
     */
    private inner class JsBridge {
        @JavascriptInterface
        fun post(envelope: String) {
            val view = webView ?: return
            view.post { handle(envelope) }
        }
    }

    companion object {
        private const val HOST_NAME = "AgimateHost"

        /**
         * Выбирает канал: `forceInterface` существует только для тестов и в приложении не
         * используется — на устройстве всегда побеждает более безопасный слушатель.
         */
        fun create(
            token: String,
            forceInterface: Boolean = false,
            onHello: () -> Unit,
            onRpc: (JsonObject) -> Unit,
        ): ViewBridge {
            val mode = if (!forceInterface && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                ViewBridgeMode.WEB_MESSAGE
            } else {
                ViewBridgeMode.JS_INTERFACE
            }
            return ViewBridge(mode, token, onHello, onRpc)
        }
    }
}
