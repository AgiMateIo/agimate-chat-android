package ru.agimate.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import java.time.Instant
import java.time.ZoneOffset

class ChatItemsTest {

    private val zone = ZoneOffset.UTC

    private fun message(
        id: String,
        stream: MessageStream,
        at: String,
        text: String = id,
    ) = ChatMessage(
        rowId = id,
        messageId = id,
        direction = if (stream == MessageStream.NONE) MessageDirection.USER else MessageDirection.AGENT,
        stream = stream,
        text = text,
        attachments = emptyList(),
        createdAt = Instant.parse(at),
    )

    @Test
    fun `consecutive progress lines collapse into one group`() {
        // Лента приходит от новых к старым.
        val feed = listOf(
            message("answer", MessageStream.ANSWER, "2026-08-15T10:00:10Z"),
            message("p3", MessageStream.PROGRESS, "2026-08-15T10:00:07Z"),
            message("p2", MessageStream.PROGRESS, "2026-08-15T10:00:05Z"),
            message("p1", MessageStream.PROGRESS, "2026-08-15T10:00:03Z"),
            message("ask", MessageStream.NONE, "2026-08-15T10:00:00Z"),
        )

        val items = buildChatItems(feed, zone)

        val groups = items.filterIsInstance<ChatItem.ProgressGroup>()
        assertEquals("десяток шагов не должен стать десятком пузырей", 1, groups.size)
        // Внутри группы шаги читаются сверху вниз, то есть в хронологическом порядке.
        assertEquals(listOf("p1", "p2", "p3"), groups.single().lines.map { it.messageId })

        val bubbles = items.filterIsInstance<ChatItem.Bubble>()
        assertEquals(listOf("answer", "ask"), bubbles.map { it.message.messageId })
    }

    @Test
    fun `progress runs separated by an answer stay separate groups`() {
        val feed = listOf(
            message("a2", MessageStream.ANSWER, "2026-08-15T10:10:00Z"),
            message("p2", MessageStream.PROGRESS, "2026-08-15T10:09:00Z"),
            message("a1", MessageStream.ANSWER, "2026-08-15T10:05:00Z"),
            message("p1", MessageStream.PROGRESS, "2026-08-15T10:04:00Z"),
        )

        val items = buildChatItems(feed, zone)

        assertEquals(2, items.filterIsInstance<ChatItem.ProgressGroup>().size)
    }

    @Test
    fun `a day separator sits above the first message of that day`() {
        val feed = listOf(
            message("today", MessageStream.ANSWER, "2026-08-15T09:00:00Z"),
            message("yesterday-2", MessageStream.ANSWER, "2026-08-14T18:00:00Z"),
            message("yesterday-1", MessageStream.NONE, "2026-08-14T17:00:00Z"),
        )

        val items = buildChatItems(feed, zone)
        val keys = items.map { it.key }

        // Список рисуется перевёрнутым, поэтому «после» в массиве означает «выше» на экране.
        // Ключ разделителя — по сообщению, над которым он встал.
        assertEquals(
            listOf("m:today", "d:today", "m:yesterday-2", "m:yesterday-1", "d:yesterday-1"),
            keys,
        )
    }

    /** Сообщение без времени разрывает ленту посередине — два разделителя одной даты. */
    @Test
    fun `a message without a timestamp does not produce a duplicate key`() {
        val feed = listOf(
            message("a", MessageStream.ANSWER, "2026-08-15T10:00:00Z"),
            message("b", MessageStream.ANSWER, "2026-08-15T09:00:00Z").copy(createdAt = null),
            message("c", MessageStream.ANSWER, "2026-08-15T08:00:00Z"),
        )

        val keys = buildChatItems(feed, zone).map { it.key }

        assertEquals("на повторившемся ключе LazyColumn падает", keys.size, keys.distinct().size)
    }

    @Test
    fun `empty feed produces no items`() {
        assertTrue(buildChatItems(emptyList(), zone).isEmpty())
    }
}
