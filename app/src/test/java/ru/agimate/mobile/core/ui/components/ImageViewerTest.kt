package ru.agimate.mobile.core.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Арифметика зума проверяется отдельно от Compose: руками ловится только «картинка уехала», а
 * почему — уже нет.
 */
class ImageViewerTest {

    private val viewport = Size(1000f, 2000f)

    /** Широкая картинка вписывается по ширине: сверху и снизу чёрные поля. */
    private val wide = Size(4000f, 2000f)

    private fun fit(image: Size = wide) = fitInside(image, viewport)

    @Test
    fun `a wide image is fitted by width`() {
        assertEquals(Size(1000f, 500f), fit())
    }

    @Test
    fun `a tall image is fitted by height`() {
        assertEquals(Size(500f, 2000f), fitInside(Size(1000f, 4000f), viewport))
    }

    /** Пока размер оригинала неизвестен, границы считаются по области — двигать нечего. */
    @Test
    fun `an unknown image size falls back to the viewport`() {
        assertEquals(viewport, fitInside(Size.Zero, viewport))
    }

    /** Ради чего всё: точка под пальцами остаётся под пальцами. */
    @Test
    fun `a pinch keeps the focal point under the fingers`() {
        val focus = Offset(200f, 100f)
        // Картинка ровно по области: двигать можно в обе стороны, ничего не упирается в границу.
        val content = fitInside(viewport, viewport)
        val zoomed = Zoom().applyGesture(
            zoom = 2f,
            pan = Offset.Zero,
            focus = focus,
            content = content,
            viewport = viewport,
        )

        assertEquals(2f, zoomed.scale, 0.001f)
        // Точка картинки = (focus - offset) / scale; до жеста это focus, после — та же точка.
        val contentPoint = (focus - zoomed.offset) / zoomed.scale
        assertEquals(focus.x, contentPoint.x, 0.001f)
        assertEquals(focus.y, contentPoint.y, 0.001f)
    }

    /**
     * Где двигать нечего, точка под пальцами не сохраняется — и не должна: широкая картинка при
     * любом масштабе ниже области, и удержать её вертикально значит оторвать от центра.
     */
    @Test
    fun `a pinch on the short axis keeps the image centered`() {
        val zoomed = Zoom().applyGesture(2f, Offset.Zero, Offset(200f, 100f), fit(), viewport)
        assertEquals(0f, zoomed.offset.y, 0.001f)
        assertEquals(-200f, zoomed.offset.x, 0.001f)
    }

    @Test
    fun `zooming out never goes below the fitted size`() {
        val zoomed = Zoom(scale = 2f).applyGesture(0.1f, Offset.Zero, Offset.Zero, fit(), viewport)
        assertEquals(1f, zoomed.scale, 0.001f)
        assertTrue(!zoomed.isZoomed)
    }

    /**
     * Регрессия. Границу задаёт вписанный размер, а не область: у широкой картинки при масштабе 2
     * по высоте видно 1000 из 2000 — за её край утащить нельзя, хотя область выше.
     */
    @Test
    fun `panning stops at the edge of the image, not of the viewport`() {
        val dragged = Zoom(scale = 2f).applyGesture(
            zoom = 1f,
            pan = Offset(0f, 9000f),
            focus = Offset.Zero,
            content = fit(),
            viewport = viewport,
        )

        // Картинка при масштабе 2 — 2000×1000 в области 1000×2000: по горизонтали 500, по
        // вертикали двигать нечего.
        assertEquals(0f, dragged.offset.y, 0.001f)
        assertEquals(500f, clampOffset(Offset(9000f, 0f), fit(), 2f, viewport).x, 0.001f)
    }

    @Test
    fun `an image smaller than the viewport cannot be dragged at all`() {
        assertEquals(
            Offset.Zero,
            clampOffset(Offset(300f, 300f), fit(), scale = 1f, viewport = viewport),
        )
    }
}
