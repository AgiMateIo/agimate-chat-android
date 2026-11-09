package ru.agimate.mobile.core.views

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.core.ui.theme.AgiColors
import ru.agimate.mobile.core.ui.theme.DarkColors
import ru.agimate.mobile.core.ui.theme.LightColors

/**
 * Таблица переменных, которую страница получает в `ui/initialize`.
 *
 * Ожидаемые значения собираются из тех же ролей [AgiColors]: тест ловит перепутанный ключ, а не
 * смену палитры. Сам формат hex проверяется отдельно, на известном цвете.
 */
class ViewHostStyleTest {

    private val hexColor = Regex("^#[0-9A-F]{6}$")

    @Test
    fun `light theme maps every documented css variable`() {
        assertEquals(
            documentedVariables(LightColors),
            viewHostContext(LightColors, locale = "ru").variables,
        )
    }

    @Test
    fun `dark theme reports dark`() {
        assertEquals("dark", viewHostContext(DarkColors, locale = "ru").theme)
        assertEquals("light", viewHostContext(LightColors, locale = "ru").theme)
    }

    @Test
    fun `locale is passed through`() {
        assertEquals("ru", viewHostContext(LightColors, locale = "ru").locale)
    }

    @Test
    fun `colors are hex`() {
        // Канал альфы отбрасывается: переменная задаёт краску, прозрачность — свойство слоя.
        assertEquals("#1C6975", Color(0xFF1C6975).toCssHex())
        assertEquals("#1C6975", Color(0x801C6975).toCssHex())

        listOf(LightColors, DarkColors).forEach { colors ->
            val values = viewHostContext(colors, locale = "ru").variables
                .filterKeys { it.startsWith("--color-") }
            assertEquals("every color role is exposed", 9, values.size)
            values.forEach { (name, value) ->
                assertTrue("$name is not hex: $value", hexColor.matches(value))
            }
        }
    }

    /** Задокументированная таблица: ключ — имя переменной из спеки, значение — роль палитры. */
    private fun documentedVariables(colors: AgiColors): Map<String, String> = mapOf(
        "--color-background-primary" to colors.background.toCssHex(),
        "--color-background-secondary" to colors.surfaceMuted.toCssHex(),
        "--color-surface-primary" to colors.surface.toCssHex(),
        "--color-text-primary" to colors.textPrimary.toCssHex(),
        "--color-text-secondary" to colors.textSecondary.toCssHex(),
        "--color-border-primary" to colors.hairline.toCssHex(),
        "--color-accent-primary" to colors.accent.toCssHex(),
        "--color-text-info" to colors.accent.toCssHex(),
        "--color-text-danger" to colors.danger.toCssHex(),
        "--font-sans" to """system-ui, -apple-system, "Segoe UI", Roboto, sans-serif""",
        "--border-radius-md" to "8px",
    )
}
