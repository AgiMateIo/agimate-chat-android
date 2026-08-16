package ru.agimate.mobile.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Семантические токены цвета. Компоненты обращаются к роли («фон карточки», «текст второго
 * плана»), а не к конкретному оттенку, — тогда смена палитры под будущие макеты правится в одном
 * файле, а не по всему проекту.
 *
 * Палитра построена от знака AgiMate: угольный #2B2C30 и золото #D6B27B. Никаких градиентов и
 * сиреневого AI-клише — спокойный плотный мессенджер.
 */
@Immutable
data class AgiColors(
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Акцент для активных состояний и мелких выделений. */
    val accent: Color,
    /** Приглушённая акцентная заливка — бейджи, подсветка выбранного. */
    val accentQuiet: Color,
    /** Заливка главной кнопки. */
    val action: Color,
    val onAction: Color,
    val bubbleOwn: Color,
    val bubbleOwnText: Color,
    val bubbleAgent: Color,
    val bubbleAgentText: Color,
    val danger: Color,
    val dangerQuiet: Color,
    val warning: Color,
    val positive: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightColors = AgiColors(
    background = Color(0xFFFAF8F4),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF2EFE9),
    hairline = Color(0xFFE6E1D8),
    textPrimary = Color(0xFF2B2C30),
    textSecondary = Color(0xFF6C6960),
    textTertiary = Color(0xFF9A968C),
    accent = Color(0xFFA97F31),
    accentQuiet = Color(0xFFF3E9D6),
    action = Color(0xFF2B2C30),
    onAction = Color(0xFFF6F2EA),
    bubbleOwn = Color(0xFFEFE6D4),
    bubbleOwnText = Color(0xFF2B2C30),
    bubbleAgent = Color(0xFFFFFFFF),
    bubbleAgentText = Color(0xFF2B2C30),
    danger = Color(0xFFA63D2C),
    dangerQuiet = Color(0xFFFAE8E3),
    warning = Color(0xFF8A6A16),
    positive = Color(0xFF3F7A4E),
    scrim = Color(0x66000000),
    isDark = false,
)

val DarkColors = AgiColors(
    background = Color(0xFF1B1C1F),
    surface = Color(0xFF232428),
    surfaceMuted = Color(0xFF2B2C30),
    hairline = Color(0xFF35363B),
    textPrimary = Color(0xFFECE8E0),
    textSecondary = Color(0xFFA29E95),
    textTertiary = Color(0xFF77746D),
    accent = Color(0xFFD6B27B),
    accentQuiet = Color(0xFF33302A),
    action = Color(0xFFD6B27B),
    onAction = Color(0xFF25262A),
    bubbleOwn = Color(0xFF3A3527),
    bubbleOwnText = Color(0xFFEFE7D8),
    bubbleAgent = Color(0xFF232428),
    bubbleAgentText = Color(0xFFECE8E0),
    danger = Color(0xFFE08A7B),
    dangerQuiet = Color(0xFF3A2723),
    warning = Color(0xFFD9B36A),
    positive = Color(0xFF7FB98C),
    scrim = Color(0x99000000),
    isDark = true,
)
