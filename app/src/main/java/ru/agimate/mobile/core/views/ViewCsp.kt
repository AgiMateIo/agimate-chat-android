package ru.agimate.mobile.core.views

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

    /** Правила ровно в том виде, в каком они уходят в заголовок ответа. */
    fun build(): String =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; object-src 'none'"
}
