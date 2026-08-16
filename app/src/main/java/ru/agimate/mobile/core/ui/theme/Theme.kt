package ru.agimate.mobile.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

val LocalAgiColors = staticCompositionLocalOf { LightColors }
val LocalAgiTypography = staticCompositionLocalOf { AgiTypographyDefaults }

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

object AgiShapes {
    val card: Shape = RoundedCornerShape(14.dp)
    val bubble: Shape = RoundedCornerShape(16.dp)
    val control: Shape = RoundedCornerShape(12.dp)
    val pill: Shape = RoundedCornerShape(percent = 50)
}

object AgiTheme {
    val colors: AgiColors
        @Composable @ReadOnlyComposable get() = LocalAgiColors.current
    val typography: AgiTypography
        @Composable @ReadOnlyComposable get() = LocalAgiTypography.current
    val spacing: AgiSpacing get() = AgiSpacing
    val shapes: AgiShapes get() = AgiShapes
}

@Composable
fun AgiMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

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
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
