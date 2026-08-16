package ru.agimate.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import java.time.Instant

class ChatMergeTest {

    private fun agent(messageId: String, text: String) = ChatMessage(
        rowId = null,
        messageId = messageId,
        direction = MessageDirection.AGENT,
        stream = MessageStream.ANSWER,
        text = text,
        attachments = emptyList(),
        createdAt = Instant.parse("2026-08-15T10:00:00Z"),
    )

    private fun optimistic(localId: String, text: String, messageId: String? = null) = ChatMessage(
        rowId = null,
        messageId = messageId,
        direction = MessageDirection.USER,
        stream = MessageStream.NONE,
        text = text,
        attachments = emptyList(),
        createdAt = Instant.parse("2026-08-15T10:00:00Z"),
        pending = messageId == null,
        localId = localId,
    )

    private fun echo(messageId: String, text: String) = ChatMessage(
        rowId = null,
        messageId = messageId,
        direction = MessageDirection.USER,
        stream = MessageStream.NONE,
        text = text,
        attachments = emptyList(),
        createdAt = Instant.parse("2026-08-15T10:00:01Z"),
    )

    @Test
    fun `new message goes to the head of the feed`() {
        val result = mergeLiveMessage(listOf(agent("m1", "первое")), agent("m2", "второе"))

        assertTrue(result.applied)
        assertEquals(listOf("m2", "m1"), result.messages.map { it.messageId })
    }

    @Test
    fun `the same messageId delivered twice is ignored`() {
        val existing = listOf(agent("m1", "готово"))

        val result = mergeLiveMessage(existing, agent("m1", "готово"))

        assertFalse("доставка at-least-once — дубль не должен попадать в ленту", result.applied)
        assertEquals(1, result.messages.size)
    }

    @Test
    fun `own echo collapses into the optimistic message it belongs to`() {
        val existing = listOf(optimistic("local-1", "посчитай расходы"))

        val result = mergeLiveMessage(existing, echo("m-42", "посчитай расходы"))

        assertTrue(result.applied)
        assertEquals("сообщение не должно раздвоиться", 1, result.messages.size)
        val merged = result.messages.single()
        assertEquals("m-42", merged.messageId)
        assertEquals("local-1", merged.localId)
        assertFalse(merged.pending)
    }

    /** Схлопывание — это правка того же элемента списка, а не появление нового. */
    @Test
    fun `own echo keeps the list key of the message it collapses into`() {
        val optimistic = optimistic("local-1", "посчитай расходы")

        val result = mergeLiveMessage(listOf(optimistic), echo("m-42", "посчитай расходы"))

        assertEquals(optimistic.key, result.messages.single().key)
    }

    @Test
    fun `echo collapses by messageId once the send response has arrived`() {
        val existing = listOf(optimistic("local-1", "привет", messageId = "m-42"))

        val result = mergeLiveMessage(existing, echo("m-42", "привет"))

        assertEquals(1, result.messages.size)
        assertEquals("local-1", result.messages.single().localId)
    }

    @Test
    fun `echo of a message sent from another device does not swallow ours`() {
        val existing = listOf(optimistic("local-1", "наше сообщение", messageId = "m-1"))

        val result = mergeLiveMessage(existing, echo("m-2", "с другого устройства"))

        assertTrue(result.applied)
        assertEquals(2, result.messages.size)
        assertEquals("m-2", result.messages.first().messageId)
        assertNull(result.messages.first().localId)
    }

    @Test
    fun `history page does not wipe messages that arrived while it was loading`() {
        // Подписка поднимается раньше истории: ответ, пришедший в это окно, второй раз не придёт.
        val live = listOf(agent("m-live", "готово"))
        val page = listOf(agent("m-2", "предыдущее"), agent("m-1", "первое"))

        val merged = mergeHistoryPage(live, page)

        assertEquals(listOf("m-live", "m-2", "m-1"), merged.map { it.messageId })
    }

    @Test
    fun `a message present in both history and the live feed is taken from history`() {
        val live = listOf(agent("m-2", "готово"))
        // У истории есть id строки — им отмечают прочтение, у живого события его нет.
        val page = listOf(
            agent("m-2", "готово").copy(rowId = "row-2"),
            agent("m-1", "первое").copy(rowId = "row-1"),
        )

        val merged = mergeHistoryPage(live, page)

        assertEquals("сообщение не должно раздвоиться", 2, merged.size)
        assertEquals("row-2", merged.first().rowId)
    }

    @Test
    fun `an optimistic message survives the history page while its send is in flight`() {
        val live = listOf(optimistic("local-1", "посчитай расходы"))
        val page = listOf(agent("m-1", "первое"))

        val merged = mergeHistoryPage(live, page)

        assertEquals(listOf("local-1", null), merged.map { it.localId })
    }

    @Test
    fun `an empty feed takes the history page as is`() {
        val page = listOf(agent("m-1", "первое"))

        assertEquals(page, mergeHistoryPage(emptyList(), page))
    }

    @Test
    fun `progress messages accumulate rather than replace each other`() {
        var feed = emptyList<ChatMessage>()
        listOf("читаю таблицу", "считаю суммы", "рисую график").forEachIndexed { index, text ->
            feed = mergeLiveMessage(
                feed,
                agent("p$index", text).copy(stream = MessageStream.PROGRESS),
            ).messages
        }

        assertEquals(3, feed.size)
        assertEquals("рисую график", feed.first().text)
    }
}
