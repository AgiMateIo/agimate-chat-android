package ru.agimate.mobile.core.views

/**
 * Политика безопасности страницы вью: что ей разрешено, и как она попадает в документ.
 *
 * Исходная точка — `default-src 'none'`: страница получает только то, что перечислено по имени.
 * Скрипты и стили — `'unsafe-inline'`, потому что MCP-приложение и есть один встроенный документ.
 * `connect-src`, `frame-src` и `base-uri` — `'none'`, а не собственный origin страницы: origin
 * у sandbox-iframe непрозрачный, и «своего» у неё нет.
 */
object ViewCsp {

    /** Правила ровно в том виде, в каком они уходят в `content` мета-тега. */
    fun build(): String =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; object-src 'none'"

    /**
     * Вставляет политику первым `<meta>` в `<head>` страницы.
     *
     * Мета-тег ставится раньше всего, что объявляет сама страница: вторая политика может только
     * сузить первую, поэтому переписать нашу страница не может. Если `<head>` нет, мета-тег
     * встаёт в начало документа.
     */
    fun withCsp(html: String): String {
        val meta = """<meta http-equiv="Content-Security-Policy" content="${build().replace("\"", "&quot;")}">"""
        val head = HEAD.find(html) ?: return meta + html
        val afterHead = head.range.last + 1
        return html.substring(0, afterHead) + meta + html.substring(afterHead)
    }

    // Граница после имени обязательна: иначе `<header>` тоже считался бы `<head>`, и мета-тег
    // попал бы внутрь `<header>`, где браузер политику не применяет.
    private val HEAD = Regex("""<head(?=[\s>])[^>]*>""", RegexOption.IGNORE_CASE)
}
