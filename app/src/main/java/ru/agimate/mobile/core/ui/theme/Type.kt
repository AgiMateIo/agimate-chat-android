package ru.agimate.mobile.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Шкала намеренно короткая: у мессенджера мало разных ролей текста, а лишние размеры разъезжаются
 * между экранами. Межстрочные интервалы плотнее материаловских — список контактов должен вмещать
 * больше строк без ощущения тесноты.
 */
@Immutable
data class AgiTypography(
    /** Заголовок экрана. */
    val title: TextStyle,
    /** Имя в строке списка, заголовок секции. */
    val subtitle: TextStyle,
    /** Основной текст — сообщения, описания. */
    val body: TextStyle,
    /** Превью последнего сообщения, подписи. */
    val secondary: TextStyle,
    /** Время, счётчики, мелкие метки. */
    val caption: TextStyle,
    /** Надписи на кнопках. */
    val action: TextStyle,
    /** Моноширинный — код внутри markdown. */
    val mono: TextStyle,
)

private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val AgiTypographyDefaults = AgiTypography(
    title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = trim,
    ),
    subtitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = trim,
    ),
    body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        lineHeightStyle = trim,
    ),
    secondary = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        lineHeightStyle = trim,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        lineHeightStyle = trim,
    ),
    action = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        lineHeightStyle = trim,
    ),
    mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = trim,
    ),
)
