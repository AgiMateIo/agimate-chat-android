package ru.agimate.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessageTest {

    private fun payload(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(
        "type" to PushMessage.TYPE_WEBCHAT_MESSAGE,
        "sessionId" to "s-1",
        *pairs,
    )

    @Test
    fun `разбирает сообщение переписки`() {
        val message = PushMessage.parse(
            payload(
                "agentId" to "a-1",
                "agentName" to "Ассистент",
                "messageId" to "m-1",
                "preview" to "Готово",
            )
        )

        assertEquals("s-1", message?.sessionId)
        assertEquals("a-1", message?.agentId)
        assertEquals("Ассистент", message?.agentName)
        assertEquals("m-1", message?.messageId)
        assertEquals("Готово", message?.preview)
    }

    @Test
    fun `чужое событие не разбирается`() {
        assertNull(PushMessage.parse(mapOf("type" to "promo", "sessionId" to "s-1")))
    }

    @Test
    fun `без переписки открывать нечего`() {
        assertNull(PushMessage.parse(mapOf("type" to PushMessage.TYPE_WEBCHAT_MESSAGE)))
        assertNull(PushMessage.parse(payload("sessionId" to " ")))
    }

    @Test
    fun `пустые поля считаются отсутствующими`() {
        val message = PushMessage.parse(payload("agentId" to "", "agentName" to "", "preview" to ""))

        assertNull(message?.agentId)
        assertNull(message?.preview)
        // Имени тоже нет. Чем его заменить, решает PushNotifier: запасное имя — перевод, а разбору
        // пуша про язык знать неоткуда.
        assertNull(message?.agentName)
    }

    @Test
    fun `открытую переписку не уведомляем`() {
        val message = requireNotNull(PushMessage.parse(payload()))

        assertFalse(shouldShowPush(message, openSessionId = "s-1", appVisible = true))
    }

    @Test
    fun `свёрнутое приложение уведомляем даже с открытым чатом`() {
        val message = requireNotNull(PushMessage.parse(payload()))

        // Экран чата остаётся в стеке вместе со своей ViewModel, поэтому переписка всё ещё
        // «открыта» — но человек её не видит.
        assertTrue(shouldShowPush(message, openSessionId = "s-1", appVisible = false))
    }

    @Test
    fun `другую переписку уведомляем`() {
        val message = requireNotNull(PushMessage.parse(payload()))

        assertTrue(shouldShowPush(message, openSessionId = "s-2", appVisible = true))
        assertTrue(shouldShowPush(message, openSessionId = null, appVisible = true))
    }
}
