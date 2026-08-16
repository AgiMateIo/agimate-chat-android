package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Знак AgiMate: три шеврона, сложенных в пирамиду. Рисуется кодом, а не картинкой, — так он
 * попадает в цвет темы и остаётся резким на любой плотности.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = AgiTheme.colors.accent,
) {
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val unit = side / 108f
        val stroke = Stroke(width = 9f * unit, cap = StrokeCap.Butt, join = StrokeJoin.Miter)

        fun chevron(halfWidth: Float, apexY: Float, baseY: Float) {
            val path = Path().apply {
                moveTo((54f - halfWidth) * unit, baseY * unit)
                lineTo(54f * unit, apexY * unit)
                lineTo((54f + halfWidth) * unit, baseY * unit)
            }
            drawPath(path, color, style = stroke)
        }

        chevron(halfWidth = 12f, apexY = 28f, baseY = 35f)
        chevron(halfWidth = 21f, apexY = 45f, baseY = 57f)
        chevron(halfWidth = 30f, apexY = 67f, baseY = 84f)
    }
}
