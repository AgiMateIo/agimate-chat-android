package ru.agimate.mobile.core.views

import java.net.URI

/**
 * Внешние домены страницы, уже разрешённые установкой.
 *
 * [resourceDomains] — скрипты, стили, картинки, шрифты и медиа; [connectDomains] — `fetch`/XHR и
 * WebSocket. Пустые списки значат «внешних доменов нет», а не «разрешено всё».
 */
data class ViewExternalDomains(
    val resourceDomains: List<String> = emptyList(),
    val connectDomains: List<String> = emptyList(),
)

/**
 * Точный `https`-origin: схема, хост и порт без пути.
 *
 * Нормализуется один раз и сравнивается как значение, поэтому политика CSP и перехватчик сети не
 * могут разойтись в том, что считать одним доменом. Порт 443 — default и в значение не входит.
 */
@JvmInline
value class ViewOrigin(val value: String) {

    companion object {
        /**
         * Из строки ответа бэкенда. Принимается только абсолютный `https`-origin без userinfo,
         * пути, query, fragment и wildcard; всё остальное — `null`.
         */
        fun parse(raw: String): ViewOrigin? {
            if (raw.isBlank() || '*' in raw) return null
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.rawUserInfo != null) return null
            val path = uri.rawPath
            if (!path.isNullOrEmpty() && path != "/") return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            return of(uri.scheme, uri.host, uri.port)
        }

        /** Из частей запроса WebView. Схема — только `https`; порт 443 и `-1` — одно и то же. */
        fun of(scheme: String?, host: String?, port: Int): ViewOrigin? {
            if (!scheme.equals("https", ignoreCase = true)) return null
            if (host.isNullOrBlank()) return null
            if (host.any { it.isWhitespace() || it in FORBIDDEN_HOST_CHARS }) return null
            if (port != -1 && port != 443 && port !in 1..65535) return null
            val normalized = host.lowercase()
            val suffix = if (port == -1 || port == 443) "" else ":$port"
            return ViewOrigin("https://$normalized$suffix")
        }

        private const val FORBIDDEN_HOST_CHARS = "*;/\\'\"@"
    }
}

/**
 * Политика одной страницы вью: заголовок CSP и origin, которые ей разрешено достичь по сети.
 *
 * Обе половины обязаны строиться вместе: заголовок без allow-list даёт неработающую вью, allow-list
 * без заголовка — дыру. Единственный вход — [ViewCsp.policy].
 */
data class ViewPolicy(
    val header: String,
    val allowedOrigins: Set<ViewOrigin>,
) {
    /** Можно ли пустить этот запрос в сеть. Сравнивается точный origin, не домен. */
    fun allows(scheme: String?, host: String?, port: Int): Boolean =
        ViewOrigin.of(scheme, host, port) in allowedOrigins
}

/**
 * Политика безопасности страницы вью: что ей разрешено.
 *
 * Политика уходит странице HTTP-заголовком `Content-Security-Policy` из
 * `shouldInterceptRequest` хоста — заголовок нельзя подавить содержимым документа. Вставка
 * `<meta>` в чужой HTML намеренно не используется: регулярка по недоверенной разметке
 * обходится — комментарий или скрипт с `<head>` перед настоящим `<head>` сдвигает вставку, —
 * и тогда политика оказывается не вторым слоем защиты, а его видимостью.
 *
 * Исходная точка — `default-src 'none'`: страница получает только то, что перечислено по имени.
 * Скрипты и стили — `'unsafe-inline'`, потому что MCP-приложение и есть один встроенный документ.
 * `connect-src`, `frame-src` и `base-uri` — `'none'`, а не собственный origin страницы: origin
 * у sandbox-iframe непрозрачный, и «своего» у неё нет.
 */
object ViewCsp {

    private val Strict = ViewPolicy(
        header = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; object-src 'none'",
        allowedOrigins = emptySet(),
    )

    /**
     * Собрать политику из разрешённых доменов.
     *
     * `null` и пустые списки дают ровно ту строгую политику, с которой платформа вышла. Домены
     * нормализует бэкенд; клиент дополнительно отбрасывает всё, что не является точным
     * `https`-origin, — последняя линия перед подстановкой в заголовок.
     */
    fun policy(domains: ViewExternalDomains?): ViewPolicy {
        if (domains == null) return Strict

        val resources = domains.resourceDomains.mapNotNull(ViewOrigin::parse).distinct()
        val connects = domains.connectDomains.mapNotNull(ViewOrigin::parse).distinct()
        if (resources.isEmpty() && connects.isEmpty()) return Strict

        return ViewPolicy(
            header = header(resources, connects),
            allowedOrigins = (resources + connects).toSet(),
        )
    }

    private fun header(resources: List<ViewOrigin>, connects: List<ViewOrigin>): String {
        val resource = resources.joinToString(" ") { it.value }
        // По CSP3 https-источник не матчит wss-URL: WebSocket разрешается только явным wss-origin.
        val connect = connects
            .flatMap { listOf(it.value, it.value.replaceFirst("https://", "wss://")) }
            .joinToString(" ")

        fun directive(name: String, base: String): String =
            if (resource.isEmpty()) "$name $base" else "$name $base $resource"

        return buildString {
            append("default-src 'none'; ")
            append(directive("script-src", "'unsafe-inline'")).append("; ")
            append(directive("style-src", "'unsafe-inline'")).append("; ")
            append(directive("img-src", "data: blob:")).append("; ")
            append(directive("font-src", "data:")).append("; ")
            append(directive("media-src", "data: blob:")).append("; ")
            append("connect-src ").append(if (connect.isEmpty()) "'none'" else connect).append("; ")
            append("frame-src 'none'; base-uri 'none'; object-src 'none'")
        }
    }
}
