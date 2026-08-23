package ru.agimate.mobile.core.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.max

/**
 * Земля приложения.
 *
 * Одного градиента подложки мало: между её концами одиннадцать уровней яркости на всю высоту
 * экрана, и такой перепад глаз без края для сравнения не ловит вовсе. На вебе он в одиночку тоже
 * не работает — лендинг кладёт поверх него светящиеся слои, и видно именно их. Здесь то же самое:
 * градиент — грунт, а красят его два пятна.
 *
 * Центры пятен вынесены за холст: на экран попадает только хвост свечения, поэтому под текстом нет
 * ни яркой точки, ни резкого края. Краски и их прозрачность — из токенов, ничего не подбиралось.
 */
@Composable
fun Modifier.backdrop(): Modifier {
    val colors = AgiTheme.colors
    return drawWithCache {
        val glow = bloom(colors.glow, x = -0.10f, y = -0.06f, radius = 1.05f)
        val aurora = bloom(colors.aurora, x = 1.10f, y = 1.02f, radius = 1.15f)
        onDrawBehind {
            drawRect(colors.backdrop)
            drawRect(glow)
            drawRect(aurora)
        }
    }
}

/**
 * Земля витрины: интро, вход, ожидание одобрения.
 *
 * Три эллипса с периодами 13, 17 и 21 секунды — рецепт бренд-бука. Периоды подобраны так, чтобы не
 * иметь заметного общего кратного: композиция не повторяется. Вращение, а не смещение —
 * сдвигающийся круг остаётся тем же кругом, а вращающийся эллипс всё время меняет силуэт.
 *
 * Только на входных экранах. Аналог лендинга в приложении — витрина, а не рабочее место: под
 * лентой сообщений ползущее пятно меняло бы контраст текста на ходу.
 */
@Composable
fun Modifier.auroraBackdrop(): Modifier {
    val colors = AgiTheme.colors
    val angles = auroraAngles()
    return drawWithCache {
        val layers = AURORA.map { layer ->
            val center = Offset(size.width * layer.x, size.height * layer.y)
            val radius = max(size.width * layer.radius, 1f)
            Triple(
                center,
                radius,
                Brush.radialGradient(
                    colors = listOf(if (layer.warm) colors.aurora else colors.glow, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
            )
        }
        onDrawBehind {
            drawRect(colors.backdrop)
            layers.forEachIndexed { index, (center, radius, brush) ->
                // Угол читается здесь, а не в блоке выше: иначе кисти пересобирались бы каждый
                // кадр вместо того, чтобы пережить всё вращение.
                //
                // Круг под сплющиванием, а не овал: круг ровно ограничивает градиент, и поворот
                // не срезает его хвост о собственные границы фигуры.
                withTransform({
                    rotate(angles[index].value, center)
                    scale(1f, AURORA[index].flatten, center)
                }) {
                    drawCircle(brush, radius = radius, center = center)
                }
            }
        }
    }
}

/** Слой сияния: доля ширины и высоты для центра, доля ширины для радиуса, сплющивание, период. */
private data class AuroraLayer(
    val x: Float,
    val y: Float,
    val radius: Float,
    val flatten: Float,
    val periodMs: Int,
    val restAngle: Float,
    val warm: Boolean,
)

private val AURORA = listOf(
    AuroraLayer(0.08f, 0.05f, 0.95f, 0.62f, periodMs = 13_000, restAngle = 0f, warm = false),
    AuroraLayer(1.02f, 0.74f, 1.05f, 0.55f, periodMs = 17_000, restAngle = 35f, warm = true),
    AuroraLayer(0.70f, 1.06f, 0.85f, 0.70f, periodMs = 21_000, restAngle = 70f, warm = false),
)

/**
 * Углы поворота слоёв.
 *
 * При выключенных анимациях бесконечная анимация не заводится вовсе, а не гасится по значению:
 * иначе кадры считались бы впустую. Ветка выбирается один раз — настройка меняется в системных
 * настройках, то есть с уходом из приложения, а возврат пересоздаёт активность.
 */
@Composable
private fun auroraAngles(): List<State<Float>> =
    if (AgiTheme.reducedMotion) {
        remember { AURORA.map { mutableFloatStateOf(it.restAngle) } }
    } else {
        val transition = rememberInfiniteTransition(label = "aurora")
        AURORA.map { layer ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    tween(layer.periodMs, easing = LinearEasing),
                    RepeatMode.Restart,
                ),
                label = "aurora-${layer.periodMs}",
            )
        }
    }

/** Пятно с центром в долях от размера холста и радиусом в долях его ширины. */
private fun CacheDrawScope.bloom(color: Color, x: Float, y: Float, radius: Float): Brush =
    Brush.radialGradient(
        colors = listOf(color, Color.Transparent),
        center = Offset(size.width * x, size.height * y),
        radius = max(size.width * radius, 1f),
    )
