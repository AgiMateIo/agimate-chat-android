package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.ui.theme.AgiTheme
import kotlin.math.min

private const val MARK_MIN_X = -3.54f
private const val MARK_MIN_Y = -0.68f
private const val MARK_WIDTH = 87.08f
private const val MARK_HEIGHT = 78.75f

/** Отношение сторон знака. Размер задаётся высотой — минимальный размер в бренд-буке дан по ней. */
const val BRAND_MARK_ASPECT = MARK_WIDTH / MARK_HEIGHT

/**
 * Ниже этого две краски перестают различаться — полосы сливаются, и знак читается грязным пятном.
 * Порог из бренд-бука: ниже него знак ставится в одну краску.
 */
private val TWO_INK_MIN = 24.dp

/** Вершина и нижний шеврон — та полоса, что несёт акцент. Верхний ромб всегда светлее. */
private const val BRIGHT_PATH =
    "M36.46 0.79 A5.0 5.0 0 0 1 43.54 0.79 L50.96 8.21 A5.0 5.0 0 0 1 50.96 15.29 " +
        "L43.54 22.71 A5.0 5.0 0 0 1 36.46 22.71 L29.04 15.29 A5.0 5.0 0 0 1 29.04 8.21 Z " +
        "M10.27 43.52 A5.0 5.0 0 0 1 10.27 39.98 L13.98 36.27 A5.0 5.0 0 0 1 17.52 36.27 " +
        "L38.23 56.98 A5.0 5.0 0 0 0 41.77 56.98 L62.48 36.27 A5.0 5.0 0 0 1 66.02 36.27 " +
        "L69.73 39.98 A5.0 5.0 0 0 1 69.73 43.52 L41.77 71.48 A5.0 5.0 0 0 1 38.23 71.48 Z"

/** Верхний шеврон и два нижних ромба — чередующаяся полоса. */
private const val DIM_PATH =
    "M10.27 20.02 A5.0 5.0 0 0 1 10.27 16.48 L13.98 12.77 A5.0 5.0 0 0 1 17.52 12.77 " +
        "L38.23 33.48 A5.0 5.0 0 0 0 41.77 33.48 L62.48 12.77 A5.0 5.0 0 0 1 66.02 12.77 " +
        "L69.73 16.48 A5.0 5.0 0 0 1 69.73 20.02 L41.77 47.98 A5.0 5.0 0 0 1 38.23 47.98 Z " +
        "M5.35 54.68 A5.0 5.0 0 0 1 12.43 54.68 L19.85 62.1 A5.0 5.0 0 0 1 19.85 69.18 " +
        "L12.43 76.6 A5.0 5.0 0 0 1 5.35 76.6 L-2.07 69.18 A5.0 5.0 0 0 1 -2.07 62.1 Z " +
        "M67.57 54.68 A5.0 5.0 0 0 1 74.65 54.68 L82.07 62.1 A5.0 5.0 0 0 1 82.07 69.18 " +
        "L74.65 76.6 A5.0 5.0 0 0 1 67.57 76.6 L60.15 69.18 A5.0 5.0 0 0 1 60.15 62.1 Z"

private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

/**
 * Знак AgiMate: ромб, два шеврона, два ромба на одной диагональной сетке.
 *
 * Контуры — те же, что в эталонном файле айдентики, до последнего знака после запятой. Рисуется
 * кодом, а не картинкой: так он попадает в цвет темы и остаётся резким на любой плотности. Правка
 * знака — правка в айдентике, а не здесь.
 *
 * Компонент принимает **одну** краску и выводит вторую прозрачностью. Иначе нельзя: на акцентной
 * плашке вызывающий передаёт белый, а светлее белого ничего нет. Следствие — на тёмной подложке
 * чередование идёт в сторону фона, а не «всегда светлее», как в двухцветном эталоне.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    /** Высота знака. Ширина считается по жёсткой пропорции — растягивать знак нельзя. */
    size: Dp = 56.dp,
    color: Color = AgiTheme.colors.accent,
) {
    val bright = remember { parse(BRIGHT_PATH) }
    val dim = remember { parse(DIM_PATH) }
    val dimColor = if (size >= TWO_INK_MIN) color.copy(alpha = 0.62f) else color

    Canvas(modifier = modifier.size(width = size * BRAND_MARK_ASPECT, height = size)) {
        val scale = min(this.size.width / MARK_WIDTH, this.size.height / MARK_HEIGHT)
        val dx = (this.size.width - MARK_WIDTH * scale) / 2f - MARK_MIN_X * scale
        val dy = (this.size.height - MARK_HEIGHT * scale) / 2f - MARK_MIN_Y * scale

        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(dim, dimColor)
            drawPath(bright, color)
        }
    }
}
