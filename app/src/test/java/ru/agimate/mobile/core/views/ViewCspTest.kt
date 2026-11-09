package ru.agimate.mobile.core.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика страницы: точный список директив и место мета-тега в документе.
 *
 * Тесты держат два свойства, от которых зависит безопасность: страница не получает ничего
 * сверх перечисленного, а наша политика оказывается в документе раньше чужой.
 */
class ViewCspTest {

    private val policy =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; object-src 'none'"

    private val ourMeta = """<meta http-equiv="Content-Security-Policy" content="$policy">"""

    @Test
    fun `policy is strict and has every directive`() {
        assertEquals(policy, ViewCsp.build())
    }

    @Test
    fun `meta goes right after the opening head tag`() {
        val html = """<html><head lang="ru" data-x="1"><script>1</script></head></html>"""

        assertEquals(
            """<html><head lang="ru" data-x="1">$ourMeta<script>1</script></head></html>""",
            ViewCsp.withCsp(html),
        )
    }

    @Test
    fun `document without head gets the meta first`() {
        val html = "<html><body>hello</body></html>"

        val result = ViewCsp.withCsp(html)

        assertTrue(result, result.startsWith(ourMeta))
    }

    @Test
    fun `header element does not count as head`() {
        val html = "<html><body><header>menu</header></body></html>"

        val result = ViewCsp.withCsp(html)

        assertTrue(result, result.startsWith(ourMeta))
    }

    @Test
    fun `an existing policy stays after ours`() {
        val theirMeta = """<meta http-equiv="Content-Security-Policy" content="default-src 'self'">"""
        val html = "<html><head>$theirMeta</head><body></body></html>"

        val result = ViewCsp.withCsp(html)

        assertTrue("ours is missing: $result", result.contains(ourMeta))
        assertTrue("theirs is missing: $result", result.contains(theirMeta))
        assertTrue(
            "ours must come first: $result",
            result.indexOf(ourMeta) < result.indexOf(theirMeta),
        )
    }
}
