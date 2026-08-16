package ru.agimate.mobile.data.webchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatMessageKeyTest {

    private fun message(
        rowId: String? = null,
        messageId: String? = null,
        localId: String? = null,
    ) = ChatMessage(
        rowId = rowId,
        messageId = messageId,
        direction = MessageDirection.AGENT,
        stream = MessageStream.ANSWER,
        text = "текст",
        attachments = emptyList(),
        createdAt = Instant.parse("2026-08-15T10:00:00Z"),
        localId = localId,
    )

    @Test
    fun `identifiers are preferred in order`() {
        assertEquals("row", message(rowId = "row", messageId = "m", localId = "local").key)
        assertEquals("local", message(messageId = "m", localId = "local").key)
        assertEquals("m", message(messageId = "m").key)
    }

    /** Все три идентификатора необязательны, а два одинаковых ключа роняют LazyColumn. */
    @Test
    fun `a message without any identifier still gets a unique key`() {
        val first = message()
        val second = message()

        assertTrue("ключ не должен быть пустым", first.key.isNotBlank())
        assertNotEquals("два безымянных сообщения — разные элементы списка", first.key, second.key)
    }

    /**
     * Правка сообщения не должна выглядеть для списка сменой элемента: ответ на отправку приносит
     * `messageId` уже после того, как пузырь нарисован.
     */
    @Test
    fun `copy keeps the key the message was created with`() {
        val optimistic = message(localId = "local-1")

        val confirmed = optimistic.copy(messageId = "m-42", pending = false)

        assertEquals("local-1", confirmed.key)
    }
}
