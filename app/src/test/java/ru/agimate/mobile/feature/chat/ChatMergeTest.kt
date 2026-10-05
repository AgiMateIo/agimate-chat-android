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

    /**
     * Выборка идёт по смещению, а лента растёт с того же конца: пришло новое сообщение — и всё
     * окно уехало на позицию. Последнее сообщение прошлой страницы приезжает первым на следующей.
     */
    @Test
    fun `a shifted pagination window does not duplicate the message on the page boundary`() {
        val loaded = listOf(
            agent("m-50", "пятидесятое").copy(rowId = "row-50"),
            agent("m-51", "пятьдесят первое").copy(rowId = "row-51"),
        )
        val older = listOf(
            // Тот же, что уже показан: страницу сдвинуло новое сообщение.
            agent("m-51", "пятьдесят первое").copy(rowId = "row-51"),
            agent("m-52", "пятьдесят второе").copy(rowId = "row-52"),
        )

        val merged = appendOlderPage(loaded, older)

        assertEquals(
            "повторившийся ключ роняет список, а не портит кадр",
            listOf("row-50", "row-51", "row-52"),
            merged.map { it.rowId },
        )
    }

    @Test
    fun `an older page meeting a live message keeps a single copy of it`() {
        // У живого сообщения ключ из messageId, у того же из истории — из rowId: совпадения
        // ключей нет, и ловится это только по messageId.
        val loaded = listOf(agent("m-9", "готово"))
        val older = listOf(
            agent("m-9", "готово").copy(rowId = "row-9"),
            agent("m-8", "раньше").copy(rowId = "row-8"),
        )

        val merged = appendOlderPage(loaded, older)

        assertEquals(listOf("m-9", "m-8"), merged.map { it.messageId })
    }

    @Test
    fun `an older page with nothing in common is appended whole`() {
        val loaded = listOf(agent("m-2", "второе").copy(rowId = "row-2"))
        val older = listOf(agent("m-1", "первое").copy(rowId = "row-1"))

        assertEquals(listOf("row-2", "row-1"), appendOlderPage(loaded, older).map { it.rowId })
    }

    @Test
    fun `an empty feed takes the older page as is`() {
        val page = listOf(agent("m-1", "первое"))

        assertEquals(page, appendOlderPage(emptyList(), page))
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

    @Test
    fun `resync starts the feed over and keeps only unsent bubbles`() {
        val oldHistory = agent("old", "давнее")
        val failed = optimistic("l1", "не ушло").copy(failed = true)
        val pending = optimistic("l2", "в пути")
        val page = listOf(agent("new", "свежее"))

        val result = resyncNewestPage(listOf(pending, failed, oldHistory), page)

        assertEquals(listOf("l2", "l1", null), result.map { it.localId })
        assertEquals("new", result.last().messageId)
    }
}
