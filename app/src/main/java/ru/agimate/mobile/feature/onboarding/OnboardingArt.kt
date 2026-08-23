package ru.agimate.mobile.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import ru.agimate.mobile.core.ui.theme.AgiTheme
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Иллюстрации интро.
 *
 * Своего набора иконок у бренда нет — это прямым текстом записано в разделе «не решено», — и
 * придумывать его ради трёх экранов не стоит. Зато можно говорить словарём самого знака: ромб на
 * диагональной сетке 45°, углы срезаны в той же пропорции, что у знака (радиус к полудиагонали
 * как 6,5 к 14,5). Фигуры разделены просветом и ничем больше — тоже правило знака.
 *
 * Рисуется в квадрате 100 x 100 и масштабируется под место: сетка задаётся числами, а не пикселями.
 */
private const val ART = 100f

/** Отношение радиуса к полудиагонали у ромба в знаке. */
private const val CORNER = 6.5f / 14.5f

/**
 * Ромб со срезанными углами. У прямого угла касательная равна радиусу, поэтому центр дуги отстоит
 * от вершины на `r * sqrt(2)` по биссектрисе, а каждая дуга занимает ровно 90°.
 */
private fun diamond(cx: Float, cy: Float, d: Float): Path {
    val r = d * CORNER
    val k = r * sqrt(2f)
    return Path().apply {
        arcTo(Rect(Offset(cx, cy - d + k), r), 225f, 90f, true)
        arcTo(Rect(Offset(cx + d - k, cy), r), 315f, 90f, false)
        arcTo(Rect(Offset(cx, cy + d - k), r), 45f, 90f, false)
        arcTo(Rect(Offset(cx - d + k, cy), r), 135f, 90f, false)
        close()
    }
}

/** Общая обвязка: квадратный холст, единицы сетки, центрирование. */
@Composable
private fun ArtCanvas(modifier: Modifier, draw: DrawScope.() -> Unit) {
    Canvas(modifier = modifier) {
        val scale = min(size.width, size.height) / ART
        withTransform({
            translate((size.width - ART * scale) / 2f, (size.height - ART * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
        }) { draw() }
    }
}

/**
 * Агент и то, чем его наделяют: коннекторы и навыки. Спутники не соединены с центром линиями —
 * в знаке фигуры тоже держатся вместе просветом, а не связями.
 */
@Composable
fun AgentWithSkills(modifier: Modifier = Modifier) {
    val colors = AgiTheme.colors
    ArtCanvas(modifier) {
        listOf(26f to 26f, 74f to 26f, 26f to 74f, 74f to 74f).forEach { (x, y) ->
            drawPath(diamond(x, y, 8f), colors.accentQuiet)
        }
        drawPath(diamond(50f, 50f, 20f), colors.accent)
    }
}

/**
 * Агент, который заговорил первым. Дуги — тёплой краской: это иллюстрация, одно из трёх мест, где
 * бренд-бук разрешает теплу выйти на передний план. Насыщать её нельзя — от янтарного
 * предупреждения тёплую краску отличает не тон, а насыщенность.
 */
@Composable
fun AgentSpeaksFirst(modifier: Modifier = Modifier) {
    val colors = AgiTheme.colors
    ArtCanvas(modifier) {
        drawPath(diamond(50f, 62f, 18f), colors.accent)
        // Дуги расходятся от верхней вершины ромба вверх: 210°..330° при оси Y вниз.
        listOf(12f, 21f, 30f).forEach { radius ->
            drawArc(
                color = colors.warm,
                startAngle = 210f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(50f - radius, 44f - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 3.4f, cap = StrokeCap.Round),
            )
        }
    }
}
