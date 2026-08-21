package ru.agimate.mobile.core.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agimate.design.AgimateTokens

val LocalAgiColors = staticCompositionLocalOf { LightColors }
val LocalAgiTypography = staticCompositionLocalOf { AgiTypographyDefaults }

/**
 * Человек попросил систему не анимировать. Веб-аналог — `prefers-reduced-motion`; бренд-бук
 * требует, чтобы длинные анимации попадали под него всегда, а не по желанию автора экрана.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Единая сетка отступов: всё в интерфейсе кратно четырём, чтобы плотность была одинаковой. */
object AgiSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    /** Ширина горизонтальных полей экрана. */
    val screen: Dp = 16.dp
}

/**
 * Радиусы — из токенов айдентики. Знак построен на 45° и острых пересечениях, интерфейс — на
 * мягком прямоугольнике; одной семьёй их делает порядок скругления, а не диагональ.
 */
object AgiShapes {
    val control: Shape = RoundedCornerShape(AgimateTokens.Radius.control)
    val card: Shape = RoundedCornerShape(AgimateTokens.Radius.card)
    /** Пузырь — крупный блок, тот же радиус, что у панели. Своей роли в токенах у него нет. */
    val bubble: Shape = RoundedCornerShape(AgimateTokens.Radius.panel)
    val panel: Shape = RoundedCornerShape(AgimateTokens.Radius.panel)
    val pill: Shape = RoundedCornerShape(percent = 50)
}

/**
 * Движение. Длительности — из токенов, кривые — из `scale.json` айдентики.
 *
 * Деление на две кривые не косметическое: [standard] — для всего, что движется под рукой человека,
 * [arrive] — для того, что появляется само.
 */
object AgiMotion {
    /** Короткое проявление: элемент должен остаться одним элементом, а не мигнуть. */
    const val crossfade: Int = AgimateTokens.Duration.crossfade
    /** Переключение навигации. */
    const val nav: Int = AgimateTokens.Duration.nav
    /** Перелёт элемента на новое место. */
    const val flight: Int = AgimateTokens.Duration.flight
    /** Пришедшее само — сообщение, событие realtime. */
    const val arrive: Int = AgimateTokens.Duration.arrive

    val standard: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val arriveEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

object AgiTheme {
    val colors: AgiColors
        @Composable @ReadOnlyComposable get() = LocalAgiColors.current
    val typography: AgiTypography
        @Composable @ReadOnlyComposable get() = LocalAgiTypography.current
    val spacing: AgiSpacing get() = AgiSpacing
    val shapes: AgiShapes get() = AgiShapes
    val motion: AgiMotion get() = AgiMotion

    /** Анимации выключены в настройках телефона. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun AgiMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current

    // Настройку читаем один раз на композицию: она меняется в системных настройках, то есть с
    // уходом из приложения, а возврат пересоздаёт активность.
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    // Material3-схема нужна компонентам из material3 (меню, диалоги, поля, индикаторы). Наши
    // токены здесь главные, и перекрыть надо **всё** семейство surfaceContainer*: у меню и
    // диалогов подложка берётся именно оттуда, а дефолт у неё сиреневый — то самое AI-клише,
    // которого в этом продукте быть не должно. Туда же surfaceTint: он подмешивает primary в
    // приподнятые поверхности.
    val scheme = (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accent,
        onPrimary = colors.onAction,
        primaryContainer = colors.accentQuiet,
        onPrimaryContainer = colors.textPrimary,
        secondary = colors.accent,
        onSecondary = colors.onAction,
        secondaryContainer = colors.accentQuiet,
        onSecondaryContainer = colors.textPrimary,
        tertiary = colors.accent,
        onTertiary = colors.onAction,
        tertiaryContainer = colors.accentQuiet,
        onTertiaryContainer = colors.textPrimary,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceMuted,
        onSurfaceVariant = colors.textSecondary,
        surfaceTint = colors.surface,
        surfaceBright = colors.surface,
        surfaceDim = colors.background,
        surfaceContainerLowest = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surfaceMuted,
        inverseSurface = colors.textPrimary,
        inverseOnSurface = colors.background,
        inversePrimary = colors.accent,
        outline = colors.hairline,
        outlineVariant = colors.hairline,
        error = colors.danger,
        onError = colors.onAction,
        errorContainer = colors.dangerQuiet,
        onErrorContainer = colors.danger,
        scrim = colors.scrim,
    )

    CompositionLocalProvider(
        LocalAgiColors provides colors,
        LocalAgiTypography provides AgiTypographyDefaults,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
