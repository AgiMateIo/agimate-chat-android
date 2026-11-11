package ru.agimate.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.agimate.mobile.core.realtime.WebchatMessagePayload
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import ru.agimate.mobile.data.webchat.WebchatAttachmentDto
import java.time.Instant

/**
 * Живое сообщение в строку ленты. Потеря `toolCallIds` здесь не видна в пузыре, но оставляет
 * `session.tool_call` без адресата: карточки не появляются до перезагрузки истории.
 */
class LiveMessageTest {

    @Test
    fun `a live progress line carries tool call ids`() {
        val message = liveMessage(
            WebchatMessagePayload(
                sessionId = "s1",
                messageId = "m1",
                direction = "AGENT",
                stream = "progress",
                text = "🔧 show_memory",
                toolCallIds = listOf("c2"),
            ),
            now = Instant.EPOCH,
        )

        assertEquals(listOf("c2"), message.toolCallIds)
        assertEquals(MessageStream.PROGRESS, message.stream)
        assertEquals(MessageDirection.AGENT, message.direction)
        assertEquals("m1", message.messageId)
        assertEquals(Instant.EPOCH, message.createdAt)
    }

    @Test
    fun `a live echo keeps its parts and an explicit timestamp`() {
        val at = Instant.parse("2026-11-11T10:00:00Z")
        val message = liveMessage(
            WebchatMessagePayload(
                messageId = "m2",
                direction = "USER",
                text = "hi",
                parts = listOf(WebchatAttachmentDto(fileId = "f1", mime = "image/png")),
                createdAt = at,
            ),
        )

        assertEquals(MessageDirection.USER, message.direction)
        assertEquals("f1", message.attachments.single().fileId)
        assertEquals(at, message.createdAt)
    }
}
