package ru.agimate.mobile.core.views

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Схемы, которые WebView обслуживает сам: это ресурсы самой страницы, а не сеть. */
private val LOCAL_SCHEMES = setOf("data", "blob", "about")

/**
 * Политика обёртки. Наследования из неё в вью нет: документ по `src` — отдельный origin, и
 * `Content-Security-Policy` вью приходит своим заголовком.
 */
internal const val WRAPPER_CSP =
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
        "frame-src https://appassets.androidplatform.net"

/**
 * Единственная точка настройки WebView, который показывает вью.
 *
 * Настройки закрытые, а не удобные: в этом WebView живёт чужой документ. Файлы и content-провайдеры
 * ему недоступны, хранилищ нет, окна не открываются, смешанный контент запрещён. Сеть закрывается
 * не здесь, а перехватом запросов в [ViewWebViewClient]: `blockNetworkLoads` не включается, потому
 * что перехваченная загрузка обёртки должна остаться возможной.
 *
 * Алгоритмическая тёмная тема выключена: цвета страница получает из `hostContext`, и второе
 * затемнение от WebView сделало бы их не теми, что задумано.
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun configureViewWebView(webView: WebView, background: Color) {
    webView.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        databaseEnabled = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        setGeolocationEnabled(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        saveFormData = false
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
    }
    webView.setBackgroundColor(background.toArgb())
}

/**
 * Глушит всё, что WebView умеет спросить у пользователя.
 *
 * Ни один запрос разрешения не доходит до экрана: камера, микрофон, геолокация и файлы в MVP не
 * выдаются, а диалог из чужого документа — готовый способ выманить согласие.
 */
internal class ViewChromeClient : WebChromeClient() {

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean {
        result.cancel()
        return true
    }

    override fun onJsBeforeUnload(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean {
        result.cancel()
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        callback.invoke(origin, false, false)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = false

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean = false
}

/**
 * Клиент, который отдаёт обёртку и документ вью ровно по одному разу и не пускает WebView в сеть.
 *
 * Оба документа приходят из лямбд: содержимое готовит [ViewHostController], а клиент отвечает за
 * одноразовость. Флаги — `AtomicBoolean`, потому что `shouldInterceptRequest` вызывается не на
 * UI-потоке, и два параллельных запроса одного адреса не должны получить документ дважды.
 *
 * Любой другой запрос получает пустой `403`, а не `null`: `null` означает «грузи сам», то есть
 * сеть. Вместе с запретом навигации это и есть блокировка сети — независимо от CSP.
 */
internal class ViewWebViewClient(
    private val wrapper: () -> String?,
    private val view: () -> String?,
    /** Политика страницы; по умолчанию строгая — внешней сети нет. */
    private val policy: () -> ViewPolicy = { ViewCsp.policy(null) },
    private val onExternalLink: (Uri) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {

    private val wrapperServed = AtomicBoolean(false)
    private val viewServed = AtomicBoolean(false)

    /*
     * Внешние домены из `csp` разрешены только здесь и только точным origin: CSP-заголовок говорит
     * браузеру, что можно, а перехватчик — что можно этому WebView. Разъехаться они не могут:
     * обе половины собраны в одном `ViewPolicy`. Всё, что не попало в allow-list, по-прежнему
     * получает пустой `403`, а не `null` («грузи сам» = сеть).
     *
     * WebSocket перехватчик не видит — его держит только `connect-src`. Редиректы после первого
     * URL тоже не перехватываются: их проверяет CSP.
     */
    override fun shouldInterceptRequest(
        webView: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme ?: return forbidden()
        if (scheme in LOCAL_SCHEMES) return null
        val isGet = request.method.equals("GET", ignoreCase = true)
        return when (url.toString()) {
            ViewWrapper.URL ->
                if (isGet) oneShot(wrapperServed, wrapper, WRAPPER_CSP) else forbidden()
            ViewWrapper.VIEW_URL ->
                if (isGet) oneShot(viewServed, view, policy().header) else forbidden()
            else ->
                if (policy().allows(scheme, url.host, url.port)) null else forbidden()
        }
    }

    /**
     * Навигацию внутрь не пускает никогда: `https` уходит во внешний обработчик, остальное молча
     * гасится. Исключение — адрес самой обёртки: её загрузку обслуживает перехват, и запретить
     * навигацию здесь значит не загрузить хост вообще.
     */
    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
        handleNavigation(request.url)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(webView: WebView, url: String): Boolean =
        handleNavigation(Uri.parse(url))

    /**
     * Рендерер упал — это не повод ронять приложение. `true` означает, что падение обработано;
     * экран покажет ошибку и пересоздаст хост по колбэку.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone()
        return true
    }

    private fun handleNavigation(uri: Uri): Boolean {
        if (uri.toString() == ViewWrapper.URL) return false
        if (uri.scheme.equals("https", ignoreCase = true)) onExternalLink(uri)
        return true
    }

    private fun oneShot(
        served: AtomicBoolean,
        html: () -> String?,
        csp: String,
    ): WebResourceResponse {
        if (!served.compareAndSet(false, true)) return forbidden()
        val body = html() ?: return forbidden()
        return htmlResponse(body, csp)
    }

    private fun htmlResponse(html: String, csp: String): WebResourceResponse = WebResourceResponse(
        "text/html",
        "utf-8",
        200,
        "OK",
        mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Security-Policy" to csp,
            "Cache-Control" to "no-store",
        ),
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )

    private fun forbidden(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Forbidden",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
