package ru.agimate.mobile.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import ru.agimate.mobile.R

/**
 * Одно семейство на всё, моноширинный — на данные: идентификаторы, время, объёмы. Моноширинный
 * здесь не украшение — продукт показывает много машинных значений, и они должны выстраиваться в
 * колонку.
 *
 * Файлы переменные: одна ось `wght` покрывает все нужные веса вместо четырёх статических
 * начертаний. Вебу этот путь закрыт — там переход на Plex стоил роста предзагрузки с 51 до 166 КБ
 * ровно потому, что шрифт непеременный; у нас minSdk 26, и оси работают начиная с него.
 *
 * Кириллица обязательна и в файлах есть: русский — основная локаль продукта, и подгружать её
 * отдельно, как это было на вебе, здесь неоткуда.
 */
private fun sans(weight: FontWeight, italic: Boolean = false) = Font(
    resId = if (italic) R.font.ibm_plex_sans_var_italic else R.font.ibm_plex_sans_var,
    weight = weight,
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun mono(weight: FontWeight) = Font(
    resId = R.font.ibm_plex_mono_var,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * Настоящий курсив, а не синтетический наклон: разметка чата курсив использует, и подставленный
 * наклон был бы виден.
 */
val PlexSans = FontFamily(
    sans(FontWeight.Normal),
    sans(FontWeight.Medium),
    sans(FontWeight.SemiBold),
    sans(FontWeight.Normal, italic = true),
    sans(FontWeight.Medium, italic = true),
    sans(FontWeight.SemiBold, italic = true),
)

val PlexMono = FontFamily(
    mono(FontWeight.Normal),
    mono(FontWeight.Medium),
)

/**
 * Шкала намеренно короткая: у мессенджера мало разных ролей текста, а лишние размеры разъезжаются
 * между экранами. Межстрочные интервалы плотнее материаловских — список контактов должен вмещать
 * больше строк без ощущения тесноты.
 *
 * Размеры задаёт мобильная сторона: шрифтовой шкалы в токенах айдентики нет — продукт живёт на
 * шкале Tailwind, у мобильных своя, и свести их пока никто не решал.
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
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = trim,
    ),
    subtitle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = trim,
    ),
    body = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        lineHeightStyle = trim,
    ),
    secondary = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        lineHeightStyle = trim,
    ),
    caption = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        lineHeightStyle = trim,
    ),
    action = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        lineHeightStyle = trim,
    ),
    mono = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = trim,
    ),
)

/**
 * Шрифт для компонентов material3 — меню, диалогов, полей. Свои стили до них не доходят, а
 * дефолтная шкала Material собрана на системной гарнитуре; без этой подмены половина интерфейса
 * набралась бы не тем шрифтом, который объявлен. Ровно эта ошибка полгода жила на вебе.
 */
private fun withPlex(base: Typography): Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = PlexSans),
    displayMedium = base.displayMedium.copy(fontFamily = PlexSans),
    displaySmall = base.displaySmall.copy(fontFamily = PlexSans),
    headlineLarge = base.headlineLarge.copy(fontFamily = PlexSans),
    headlineMedium = base.headlineMedium.copy(fontFamily = PlexSans),
    headlineSmall = base.headlineSmall.copy(fontFamily = PlexSans),
    titleLarge = base.titleLarge.copy(fontFamily = PlexSans),
    titleMedium = base.titleMedium.copy(fontFamily = PlexSans),
    titleSmall = base.titleSmall.copy(fontFamily = PlexSans),
    bodyLarge = base.bodyLarge.copy(fontFamily = PlexSans),
    bodyMedium = base.bodyMedium.copy(fontFamily = PlexSans),
    bodySmall = base.bodySmall.copy(fontFamily = PlexSans),
    labelLarge = base.labelLarge.copy(fontFamily = PlexSans),
    labelMedium = base.labelMedium.copy(fontFamily = PlexSans),
    labelSmall = base.labelSmall.copy(fontFamily = PlexSans),
)

val PlexMaterialTypography: Typography = withPlex(Typography())
