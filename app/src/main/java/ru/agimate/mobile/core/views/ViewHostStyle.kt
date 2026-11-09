package ru.agimate.mobile.core.views

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ru.agimate.mobile.core.ui.theme.AgiColors
import ru.agimate.mobile.design.AgimateTokens

/**
 * Контекст хоста для `ui/initialize`: тема, локаль и CSS-переменные, которыми страница
 * подстраивается под приложение.
 *
 * Таблица переменных закрыта и повторяет документированный набор MCP Apps: страница не должна
 * видеть роли, которых нет в контракте, поэтому лишних ключей здесь нет. Снимок берётся на момент
 * инициализации — тему приложения страница сама не переключает.
 */
fun viewHostContext(colors: AgiColors, locale: String): ViewHostContext = ViewHostContext(
    theme = if (colors.isDark) "dark" else "light",
    locale = locale,
    variables = mapOf(
        "--color-background-primary" to colors.background.toCssHex(),
        "--color-background-secondary" to colors.surfaceMuted.toCssHex(),
        "--color-surface-primary" to colors.surface.toCssHex(),
        "--color-text-primary" to colors.textPrimary.toCssHex(),
        "--color-text-secondary" to colors.textSecondary.toCssHex(),
        "--color-border-primary" to colors.hairline.toCssHex(),
        "--color-accent-primary" to colors.accent.toCssHex(),
        "--color-text-info" to colors.accent.toCssHex(),
        "--color-text-danger" to colors.danger.toCssHex(),
        "--font-sans" to SYSTEM_FONT_STACK,
        "--border-radius-md" to "${AgimateTokens.Radius.control.value.toInt()}px",
    ),
)

/**
 * Цвет как `#RRGGBB` — единственный формат, в котором переменные понимает страница.
 *
 * Альфа отбрасывается намеренно: переменная задаёт краску, а прозрачность — свойство конкретного
 * слоя. Попади альфа в чужую страницу, та получила бы влияние на композицию, о котором не просила.
 */
fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

/**
 * Семейства шрифтов в айдентике нет — интерфейс набран системным шрифтом. Стек повторяет
 * Compose-шный по умолчанию, чтобы текст на странице и текст приложения выглядели одинаково.
 */
private const val SYSTEM_FONT_STACK = """system-ui, -apple-system, "Segoe UI", Roboto, sans-serif"""
