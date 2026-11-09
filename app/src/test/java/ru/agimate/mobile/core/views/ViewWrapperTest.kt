package ru.agimate.mobile.core.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сборка обёртки — чистая подстановка, поэтому проверяется без Android: шаблон, токен, цвет и
 * адрес вью склеиваются здесь, а всё поведение обёртки живёт в host.html и проверяется
 * инструментальным тестом моста.
 */
class ViewWrapperTest {

    @Test
    fun `build replaces token, background and view url`() {
        val template = """
            <html>
            <script>
              var TOKEN = "__TOKEN__";
              var VIEW = "__VIEW_URL__";
            </script>
            <style>body { background: __BACKGROUND__; }</style>
            </html>
        """.trimIndent()

        val html = ViewWrapper.build(
            hostHtml = template,
            viewUrl = ViewWrapper.VIEW_URL,
            token = "8a1b6c9e-token",
            background = "#123456",
        )

        assertTrue(html.contains(ViewWrapper.VIEW_URL))
        assertTrue(html.contains("#123456"))
        assertTrue(html.contains("8a1b6c9e-token"))
        assertTrue(!html.contains("__TOKEN__"))
        assertTrue(!html.contains("__BACKGROUND__"))
        assertTrue(!html.contains("__VIEW_URL__"))
    }

    @Test
    fun `both documents live on the appassets origin`() {
        assertEquals("https://appassets.androidplatform.net", ViewWrapper.ORIGIN)
        assertEquals("https://appassets.androidplatform.net/views/host.html", ViewWrapper.URL)
        assertEquals("https://appassets.androidplatform.net/views/page.html", ViewWrapper.VIEW_URL)
    }

    @Test
    fun `the host asset carries every placeholder build substitutes`() {
        // Рабочий каталог юнит-тестов — каталог модуля, но на всякий случай ищем и от корня.
        val asset = listOf(
            File("src/main/assets/views/host.html"),
            File("app/src/main/assets/views/host.html"),
        ).firstOrNull { it.exists() }
        assertNotNull("host.html не найден от ${File(".").absolutePath}", asset)

        val html = asset!!.readText()
        listOf("__TOKEN__", "__BACKGROUND__", "__VIEW_URL__").forEach { placeholder ->
            assertTrue("в host.html нет $placeholder", html.contains(placeholder))
        }
    }
}
