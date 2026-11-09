package ru.agimate.mobile.core.views

import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import ru.agimate.mobile.R
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.ui.text.resolve
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Связывает WebView, мост и протокол в один хост вью.
 *
 * Токен канала рождается здесь и на экземпляр один: перезагрузка вью пересоздаёт контроллер, а
 * значит и токен. `start` и `release` вызываются с UI-потока — так их зовёт Compose, и WebView
 * требует того же. Контроллер это условие не проверяет и не перекладывает вызов в очередь.
 */
internal class ViewHostController(
    private val webView: WebView,
    private val hostHtml: String,
    private val content: String,
    private val hostContext: ViewHostContext,
    private val background: Color,
    /** Текст ошибки для страницы; серверное сообщение главнее общего. */
    private val toolErrorText: (Throwable) -> String,
    private val onCallTool: suspend (String, JsonObject) -> JsonObject,
    /** `null` — ссылка открыта; иначе — текст ошибки для страницы. */
    private val onOpenLink: (Uri) -> String?,
    private val onRendererGone: () -> Unit,
    private val scope: CoroutineScope,
) {

    private val token = UUID.randomUUID().toString()

    private var wrapperHtml: String? = null
    private var bridge: ViewBridge? = null
    private var started = false

    /**
     * Готовит WebView и загружает обёртку. Повторный вызов — ошибка: второй `install` моста
     * зарегистрировал бы второй слушатель с тем же именем.
     */
    fun start() {
        check(!started) { "ViewHostController запускается один раз" }
        started = true
        configureViewWebView(webView, background)
        val wrapper = ViewWrapper.build(
            hostHtml = hostHtml,
            viewUrl = ViewWrapper.VIEW_URL,
            token = token,
            background = background.toCssHex(),
        )
        // Лямбда клиента читает поле лениво: на момент установки клиента HTML уже собран.
        wrapperHtml = wrapper
        val installed = ViewBridge.create(
            token = token,
            onHello = {},
            onRpc = ::dispatchMessage,
        )
        // Мост ставится до загрузки: hello из обёртки иначе некому принять.
        installed.install(webView)
        bridge = installed
        webView.webChromeClient = ViewChromeClient()
        webView.webViewClient = ViewWebViewClient(
            wrapper = { wrapperHtml },
            view = { content },
            onExternalLink = { uri -> onOpenLink(uri) },
            onRendererGone = onRendererGone,
        )
        webView.loadUrl(ViewWrapper.URL)
    }

    /**
     * Снимает мост и закрывает выдачу документов: после этого перехват отвечает `403`.
     * Вызывается с UI-потока; повторный вызов безопасен.
     */
    fun release() {
        bridge?.release()
        bridge = null
        wrapperHtml = null
    }

    private fun dispatchMessage(message: JsonObject) {
        // Мост отдаёт уже разобранный объект, а редуктор протокола принимает строку — граница
        // держится там, чтобы разбор вёлся в одном месте и на одном парсере.
        dispatch(ViewProtocol.decode(message.toString(), hostContext))
    }

    private fun dispatch(action: ViewAction) {
        when (action) {
            is ViewAction.Reply -> bridge?.send(action.reply)
            is ViewAction.Call -> callTool(action)
            is ViewAction.OpenLink -> openLink(action)
            ViewAction.Ready, is ViewAction.Resize, ViewAction.Ignore -> Unit
        }
    }

    /** Ответ уходит странице в том же порядке, в каком пришёл вызов: свой id у каждого запроса. */
    private fun callTool(action: ViewAction.Call) {
        scope.launch {
            val reply = try {
                ViewProtocol.resultReply(action.id, onCallTool(action.name, action.arguments))
            } catch (cancelled: CancellationException) {
                // Отмена — не ошибка тула: ответ на отменённый вызов никому не нужен.
                throw cancelled
            } catch (error: Throwable) {
                ViewProtocol.errorReply(
                    action.id,
                    ViewProtocol.INTERNAL_ERROR,
                    toolErrorText(error),
                )
            }
            bridge?.send(reply)
        }
    }

    private fun openLink(action: ViewAction.OpenLink) {
        val failure = onOpenLink(Uri.parse(action.url.value))
        val reply = if (failure == null) {
            ViewProtocol.resultReply(action.id, JsonObject(emptyMap()))
        } else {
            ViewProtocol.errorReply(action.id, ViewProtocol.INTERNAL_ERROR, failure)
        }
        bridge?.send(reply)
    }
}

/**
 * WebView вью в Compose.
 *
 * Контроллер создаётся в `factory` и живёт до `onRelease`: `ViewHostController` не переживает
 * WebView, который обслуживает. Перезагрузка документа — это пересоздание хоста снаружи, ключом
 * вроде `key(revision)`: токен канала выдаётся на экземпляр и повторно не используется.
 */
@Composable
fun AgentViewHost(
    content: String,
    hostContext: ViewHostContext,
    background: Color,
    onCallTool: suspend (String, JsonObject) -> JsonObject,
    onOpenLink: (Uri) -> String?,
    onRendererGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Шаблон обёртки не меняется между перезагрузками — читается один раз на композицию.
    val hostHtml = remember { ViewWrapper.hostHtml(context) }
    // `factory` и `onRelease` — разные лямбды AndroidView, и общая ссылка нужна им одна.
    val controller = remember { AtomicReference<ViewHostController?>() }

    AndroidView(
        factory = { viewContext ->
            WebView(viewContext).also { webView ->
                val host = ViewHostController(
                    webView = webView,
                    hostHtml = hostHtml,
                    content = content,
                    hostContext = hostContext,
                    background = background,
                    toolErrorText = { error ->
                        (error as? ApiException)?.text?.resolve(viewContext)
                            ?: viewContext.getString(R.string.views_tool_call_failed)
                    },
                    onCallTool = onCallTool,
                    onOpenLink = onOpenLink,
                    onRendererGone = onRendererGone,
                    scope = scope,
                )
                controller.set(host)
                host.start()
            }
        },
        onRelease = { webView ->
            controller.getAndSet(null)?.release()
            webView.destroy()
        },
        modifier = modifier,
    )
}
