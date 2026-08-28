package ru.agimate.mobile.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import ru.agimate.mobile.design.AgimateTokens

/**
 * Семантические токены цвета. Компоненты обращаются к роли («фон карточки», «текст второго
 * плана»), а не к конкретному оттенку.
 *
 * Значения не подбираются здесь, а берутся из [AgimateTokens] — сгенерированного файла айдентики,
 * общего у веба, Android и iOS. Правило бренд-бука: продуктовый код имеет право только на роли,
 * примитивы («бирюза 500») сюда не попадают вовсе.
 *
 * Палитра v1: тёплые нейтрали Mocha Mousse и холодный бирюзовый акцент. Контраст температур и есть
 * характер — тёплая подложка, холодный сигнал.
 */
@Immutable
data class AgiColors(
    val background: Color,
    /**
     * Земля приложения. Не поверхность и не роль карточки: в айдентике подложка — своя плоскость,
     * намеренно отвязанная от `surface`, иначе она уезжала бы каждый раз, когда меняются карточки.
     *
     * Кисть, а не цвет: концы градиента — два отдельных токена, и собирать их на каждом экране
     * значило бы иметь столько же мнений о том, куда он течёт. Растягивается по высоте того, на
     * чём нарисована, поэтому красить ею полагается корень экрана, а не полосу внутри него.
     */
    val backdrop: Brush,
    /**
     * Холодное пятно света на земле. В айдентике это «ореол активации агента» — краска
     * акцента с зашитой прозрачностью; свечению подложки она годится ровно тем же.
     */
    val glow: Color,
    /**
     * Тёплое пятно на земле, пиковый тон одного слоя сияния. На светлой теме краска гуще:
     * светлому грунту нужно больше тона, чтобы намыв был виден так же.
     */
    val aurora: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Акцент для активных состояний и мелких выделений. */
    val accent: Color,
    /**
     * Акцент, пригодный для мелкого текста. На тёмной теме у основного акцента контраст к фону
     * 4,32 — ниже порога 4,5, поэтому там роль занимает светлая краска знака (9,67). На светлой
     * акцент проходит с запасом и роли совпадают.
     */
    val accentText: Color,
    /** Приглушённая акцентная заливка — бейджи, подсветка выбранного. */
    val accentQuiet: Color,
    /** Заливка главной кнопки. Действия принадлежат акценту — это правило, а не выбор. */
    val action: Color,
    val onAction: Color,
    /**
     * Тёплая краска, Mocha Mousse. Единственный тёплый цвет, которому разрешено выйти на передний
     * план: пустые состояния, иллюстрации, декоративные пометки. Ничего интерактивного — действия
     * принадлежат акценту. Насыщать нельзя: от янтарного предупреждения её отличает не тон
     * (между ними 20°), а насыщенность — земля против сигнала.
     */
    val warm: Color,
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

/**
 * Роли, которых в айдентике нет: мессенджера бренд-бук не покрывает, и ролей `bubble-*`,
 * «приглушённой заливки» и «текста третьего плана» в токенах не существует.
 *
 * Поэтому они не подбираются на глаз, а выводятся из существующих ролей смешиванием — тогда смена
 * токена тянет их за собой сама. Если пузыри однажды понадобятся вебу, роли надо будет завести в
 * `theme.json` айдентики, и тогда этот блок исчезнет.
 */
private fun quiet(ink: Color, over: Color, alpha: Float) = ink.copy(alpha = alpha).compositeOver(over)

val LightColors = with(AgimateTokens.Colors.Light) {
    AgiColors(
        background = background,
        backdrop = Brush.verticalGradient(listOf(backdropStart, backdropEnd)),
        glow = accentGlow,
        aurora = auroraTint,
        surface = surface,
        surfaceMuted = surfaceSecondary,
        hairline = border,
        textPrimary = foreground,
        textSecondary = muted,
        textTertiary = quiet(muted, background, 0.62f),
        accent = accent,
        accentText = accent,
        accentQuiet = quiet(accent, surface, 0.16f),
        action = accent,
        onAction = accentForeground,
        warm = warm,
        bubbleOwn = quiet(accent, surface, 0.14f),
        bubbleOwnText = foreground,
        bubbleAgent = surface,
        bubbleAgentText = foreground,
        danger = error,
        dangerQuiet = quiet(error, surface, 0.12f),
        warning = warning,
        positive = success,
        scrim = Color(0x66000000),
        isDark = false,
    )
}

val DarkColors = with(AgimateTokens.Colors.Dark) {
    AgiColors(
        background = background,
        backdrop = Brush.verticalGradient(listOf(backdropStart, backdropEnd)),
        glow = accentGlow,
        aurora = auroraTint,
        surface = surface,
        surfaceMuted = surfaceSecondary,
        hairline = border,
        textPrimary = foreground,
        textSecondary = muted,
        textTertiary = quiet(muted, background, 0.62f),
        accent = accent,
        accentText = markInkLight,
        accentQuiet = quiet(accent, surface, 0.20f),
        action = accent,
        onAction = accentForeground,
        warm = warm,
        bubbleOwn = quiet(accent, surface, 0.18f),
        bubbleOwnText = foreground,
        bubbleAgent = surface,
        bubbleAgentText = foreground,
        danger = error,
        dangerQuiet = quiet(error, surface, 0.16f),
        warning = warning,
        positive = success,
        scrim = Color(0x99000000),
        isDark = true,
    )
}
