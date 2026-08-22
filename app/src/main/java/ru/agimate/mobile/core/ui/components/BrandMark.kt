package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.ui.theme.AgiTheme
import kotlin.math.min

private const val MARK_MIN_X = 3.67f
private const val MARK_MIN_Y = -0.06f
private const val MARK_WIDTH = 72.66f
private const val MARK_HEIGHT = 66.09f

/** Отношение сторон знака. Размер задаётся высотой — минимальный размер в бренд-буке дан по ней. */
const val BRAND_MARK_ASPECT = MARK_WIDTH / MARK_HEIGHT

/**
 * Порог фасетки из бренд-бука. Ниже него перепад светлоты внутри фигуры занимает считанные
 * пиксели: градиент уже не читается, а его тёмный конец тратит контраст, который на мелком нужен
 * целиком. Поэтому ниже — одна плоская краска, а не выцветающая.
 */
private val FACET_MIN = 40.dp

/** Тёмный конец фасетки. Столько же берёт блик на плитке приложения — альфа в системе одна. */
private const val FACET_DIM = 0.62f

/**
 * Четыре контура знака: шеврон, вершина, два ромба основания. Порядок — как в эталонном файле.
 *
 * Фигуры рисуются по отдельности не ради удобства: фасетка красит каждую своим градиентом, от её
 * собственного верхнего левого угла к нижнему правому. Одна заливка на объединённый контур дала бы
 * знаку общий градиент, у которого светлый угол только один.
 */
private val MARK_PATHS = listOf(
    "M5.57 20.82 A6.5 6.5 0 0 1 5.57 11.63 L6.38 10.82 A6.5 6.5 0 0 1 15.57 10.82 " +
        "L35.4 30.65 A6.5 6.5 0 0 0 44.6 30.65 L64.43 10.82 A6.5 6.5 0 0 1 73.62 10.82 " +
        "L74.43 11.63 A6.5 6.5 0 0 1 74.43 20.82 L44.6 50.65 A6.5 6.5 0 0 1 35.4 50.65 Z",
    "M35.4 1.85 A6.5 6.5 0 0 1 44.6 1.85 L49.9 7.15 A6.5 6.5 0 0 1 49.9 16.35 " +
        "L44.6 21.65 A6.5 6.5 0 0 1 35.4 21.65 L30.1 16.35 A6.5 6.5 0 0 1 30.1 7.15 Z",
    "M10.88 44.32 A6.5 6.5 0 0 1 20.07 44.32 L25.38 49.63 A6.5 6.5 0 0 1 25.38 58.82 " +
        "L20.07 64.13 A6.5 6.5 0 0 1 10.88 64.13 L5.57 58.82 A6.5 6.5 0 0 1 5.57 49.63 Z",
    "M59.93 44.32 A6.5 6.5 0 0 1 69.12 44.32 L74.43 49.63 A6.5 6.5 0 0 1 74.43 58.82 " +
        "L69.12 64.13 A6.5 6.5 0 0 1 59.93 64.13 L54.62 58.82 A6.5 6.5 0 0 1 54.62 49.63 Z",
)

private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

/**
 * Знак AgiMate: вершина, шеврон и два ромба основания на одной диагональной сетке.
 *
 * Контуры — те же, что в эталонном файле айдентики, до последнего знака после запятой. Рисуется
 * кодом, а не картинкой: так он попадает в цвет темы и остаётся резким на любой плотности. Правка
 * знака — правка в айдентике, а не здесь.
 *
 * Компонент принимает **одну** краску и выводит из неё фасетку прозрачностью. Иначе нельзя: на
 * акцентной плашке вызывающий передаёт белый, а светлее белого ничего нет. Следствие — на тёмной
 * подложке фасетка уходит в сторону фона, а не «всегда светлее», как в двухцветном эталоне.
 *
 * Направление фасетки жёсткое: свет всегда из верхнего левого угла. Это не косметика — одинаковый
 * угол света и связывает четыре отдельные фигуры в один предмет.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    /** Высота знака. Ширина считается по жёсткой пропорции — растягивать знак нельзя. */
    size: Dp = 56.dp,
    color: Color = AgiTheme.colors.accent,
) {
    val paths = remember { MARK_PATHS.map { parse(it) } }
    val faceted = size >= FACET_MIN

    Canvas(modifier = modifier.size(width = size * BRAND_MARK_ASPECT, height = size)) {
        val scale = min(this.size.width / MARK_WIDTH, this.size.height / MARK_HEIGHT)
        val dx = (this.size.width - MARK_WIDTH * scale) / 2f - MARK_MIN_X * scale
        val dy = (this.size.height - MARK_HEIGHT * scale) / 2f - MARK_MIN_Y * scale

        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            paths.forEach { path ->
                // Кисть задаётся в текущей системе координат, то есть в единицах сетки знака:
                // границы фигуры можно брать прямо у контура, пересчитывать под масштаб не нужно.
                val brush = if (faceted) {
                    val box = path.getBounds()
                    Brush.linearGradient(
                        colors = listOf(color, color.copy(alpha = FACET_DIM)),
                        start = box.topLeft,
                        end = box.bottomRight,
                    )
                } else {
                    SolidColor(color)
                }
                drawPath(path, brush)
            }
        }
    }
}
