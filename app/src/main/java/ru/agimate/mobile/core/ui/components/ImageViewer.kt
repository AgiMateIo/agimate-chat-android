package ru.agimate.mobile.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import ru.agimate.mobile.core.ui.theme.AgiTheme
import kotlin.math.max
import kotlin.math.min

/** Что показывает просмотрщик: готовый адрес и подпись для screen reader. */
data class ViewerImage(val url: String, val name: String?)

/** Дальше пиксели уже не разглядеть, а жест становится неуправляемым. */
private const val MAX_SCALE = 5f

/** Во сколько увеличивает двойной тап. */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Полноэкранный просмотр изображения: щипок, перетаскивание, двойной тап.
 *
 * Рисуется поверх экрана, а не отдельным маршрутом навигации: адрес картинки подписан и живёт 15
 * минут, и класть его в back stack значит когда-нибудь вернуться к нему протухшим.
 */
@Composable
fun ImageViewer(image: ViewerImage, onClose: () -> Unit) {
    val colors = AgiTheme.colors

    var viewport by remember { mutableStateOf(Size.Zero) }
    // Размер оригинала нужен не для показа, а для границ сдвига: без него непонятно, где у
    // увеличенной картинки край, и её можно утащить в пустоту.
    var intrinsic by remember(image.url) { mutableStateOf(Size.Zero) }
    var zoom by remember(image.url) { mutableStateOf(Zoom()) }
    var failed by remember(image.url) { mutableStateOf(false) }
    var ready by remember(image.url) { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = it.toSize() }
            .pointerInput(image.url) {
                detectTapGestures(
                    // Тап по увеличенной картинке возвращает её в размер экрана, а не закрывает:
                    // случайно потерять то, что человек только что разглядывал, обиднее.
                    onTap = { if (zoom.isZoomed) zoom = Zoom() else onClose() },
                    onDoubleTap = { point ->
                        val content = fitInside(intrinsic, viewport)
                        zoom = if (zoom.isZoomed) {
                            Zoom()
                        } else {
                            zoom.applyGesture(
                                zoom = DOUBLE_TAP_SCALE,
                                pan = Offset.Zero,
                                focus = point - centerOf(viewport),
                                content = content,
                                viewport = viewport,
                            )
                        }
                    },
                )
            }
            .pointerInput(image.url) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    zoom = zoom.applyGesture(
                        zoom = gestureZoom,
                        pan = pan,
                        focus = centroid - centerOf(viewport),
                        content = fitInside(intrinsic, viewport),
                        viewport = viewport,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = image.url,
            contentDescription = image.name,
            contentScale = ContentScale.Fit,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> {
                        intrinsic = state.painter.intrinsicSize
                        ready = true
                    }

                    is AsyncImagePainter.State.Error -> failed = true
                    else -> Unit
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
        )

        if (!ready && !failed) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }

        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.xs),
                modifier = Modifier.padding(AgiTheme.spacing.screen),
            ) {
                Text(
                    text = "Не удалось загрузить изображение",
                    style = AgiTheme.typography.body,
                    color = Color.White,
                )
                // Ссылки на файлы подписаны и живут 15 минут — у открытого чата они протухают.
                Text(
                    text = "Ссылка живёт 15 минут. Откройте чат заново — придёт свежая.",
                    style = AgiTheme.typography.caption,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Закрыть",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(AgiTheme.spacing.sm)
                .background(colors.scrim, CircleShape)
                .padding(AgiTheme.spacing.xs)
                .size(24.dp)
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        )
    }
}

/** Масштаб и сдвиг картинки в пикселях от центра области. */
internal data class Zoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val isZoomed: Boolean get() = scale > 1f
}

internal fun centerOf(viewport: Size) = Offset(viewport.width / 2f, viewport.height / 2f)

/**
 * Шаг жеста: [zoom] — множитель щипка, [pan] — сдвиг пальцами, [focus] — центр щипка относительно
 * центра области.
 *
 * Уменьшить меньше, чем «вписано в экран», нельзя: иначе картинка гуляет крохотным пятном посреди
 * чёрного поля, и непонятно, как вернуть её обратно.
 */
internal fun Zoom.applyGesture(
    zoom: Float,
    pan: Offset,
    focus: Offset,
    content: Size,
    viewport: Size,
): Zoom {
    val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
    val ratio = next / scale
    // Точка под пальцами обязана остаться под пальцами — иначе картинка уезжает из-под щипка.
    val moved = focus * (1 - ratio) + offset * ratio + pan
    return Zoom(next, clampOffset(moved, content, next, viewport))
}

/**
 * Границы сдвига: за край увеличенной картинки уехать нельзя, а пока она меньше области — двигать
 * нечего. Считается по вписанному размеру, а не по области: у узкой картинки поля по бокам чёрные,
 * и таскать её по ним значит терять её из виду.
 */
internal fun clampOffset(offset: Offset, content: Size, scale: Float, viewport: Size): Offset {
    val limitX = max(0f, (content.width * scale - viewport.width) / 2f)
    val limitY = max(0f, (content.height * scale - viewport.height) / 2f)
    return Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
}

/** Размер картинки, вписанной в область (`ContentScale.Fit`) — то, что видно при масштабе 1. */
internal fun fitInside(image: Size, viewport: Size): Size {
    if (image.width <= 0f || image.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) {
        return viewport
    }
    val scale = min(viewport.width / image.width, viewport.height / image.height)
    return Size(image.width * scale, image.height * scale)
}
