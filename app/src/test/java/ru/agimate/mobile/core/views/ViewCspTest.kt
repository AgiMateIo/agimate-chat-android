package ru.agimate.mobile.core.views

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Политика страницы: точный список директив.
 *
 * Политика отдаётся заголовком ответа, поэтому документа в тестах нет — проверяется только
 * сама строка, от которой зависит, что страница не получит ничего сверх перечисленного.
 */
class ViewCspTest {

    @Test
    fun `policy is strict and has every directive`() {
        assertEquals(
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
                "frame-src 'none'; base-uri 'none'; object-src 'none'",
            ViewCsp.build(),
        )
    }
}
