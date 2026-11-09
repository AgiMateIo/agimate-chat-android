package ru.agimate.mobile.core.views

import android.content.Context

/**
 * Доверенный документ, в котором живёт страница коннектора.
 *
 * Вью приходит с сервера и потому недоверенна, а обёртка собирается здесь и получает токен. Вью
 * кладётся в `<iframe sandbox="allow-scripts">`: она не видит ни DOM обёртки, ни её токен, и
 * говорит с нативом только через `postMessage` — в обе стороны через обёртку.
 *
 * Оба адреса — на зарезервированном `appassets.androidplatform.net` (его использует и
 * WebViewAssetLoader из androidx.webkit): домен не существует в сети, а origin ровно один, и
 * правило `allowedOriginRules` у WebMessageListener задаётся одной строкой.
 */
object ViewWrapper {

    const val ORIGIN = "https://appassets.androidplatform.net"
    const val URL = "$ORIGIN/views/host.html"
    const val VIEW_URL = "$ORIGIN/views/page.html"

    private const val ASSET = "views/host.html"

    /**
     * Подставляет в шаблон токен канала, цвет фона и адрес вью.
     *
     * HTML вью сюда не попадает: его отдаёт отдельный ответ на отдельном адресе, поэтому
     * подставить в обёртку чужую разметку структурно невозможно.
     */
    fun build(hostHtml: String, viewUrl: String, token: String, background: String): String =
        hostHtml
            .replace("__TOKEN__", token)
            .replace("__BACKGROUND__", background)
            .replace("__VIEW_URL__", viewUrl)

    /** Читает шаблон обёртки из assets. */
    fun hostHtml(context: Context): String =
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
