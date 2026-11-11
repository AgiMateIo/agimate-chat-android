package ru.agimate.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.data.views.ToolCallDescriptor
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import java.time.Instant

/**
 * Карточки собираются из строк группы и карты сигналов. Здесь проверяется только чистая часть:
 * порядок, приоритет сигнала, скрытие снятых карточек и кламп высоты.
 */
class InlineViewsTest {

    private fun line(
        id: String,
        ids: List<String>,
        calls: List<ToolCallDescriptor>? = null,
    ) = ChatMessage(
        rowId = id,
        messageId = id,
        direction = MessageDirection.AGENT,
        stream = MessageStream.PROGRESS,
        text = id,
        attachments = emptyList(),
        createdAt = Instant.EPOCH,
        toolCallIds = ids,
        toolCalls = calls,
    )

    private fun group(vararg lines: ChatMessage) =
        ChatItem.ProgressGroup(key = "p:g", lines = lines.toList())

    private fun descriptor(id: String, view: String? = "ui://v") = ToolCallDescriptor(
        toolCallId = id,
        name = "show_$id",
        connectionId = "k1",
        viewUri = view,
    )

    @Test
    fun `cards follow line order and id order without duplicates`() {
        val g = group(
            line("l1", listOf("c1", "c2"), listOf(descriptor("c1"), descriptor("c2"))),
            line("l2", listOf("c2", "c3"), listOf(descriptor("c2"), descriptor("c3"))),
        )

        val cards = InlineViewsState().cardsFor(g)

        assertEquals(listOf("c1", "c2", "c3"), cards.map { it.toolCallId })
    }

    @Test
    fun `a signal descriptor wins over the history one`() {
        val history = descriptor("c1", view = "ui://old")
        val signal = descriptor("c1", view = "ui://new")
        val state = InlineViewsState(overrides = mapOf("c1" to signal))

        val card = state.cardsFor(group(line("l1", listOf("c1"), listOf(history)))).single()

        assertEquals("ui://new", card.descriptor.viewUri)
        assertEquals(signal, card.descriptor)
    }

    @Test
    fun `no view, no connection or a withdrawn card gives no card`() {
        val g = group(
            line(
                "l1",
                listOf("no-view", "no-connection", "withdrawn"),
                listOf(
                    descriptor("no-view", view = null),
                    descriptor("no-connection").copy(connectionId = null),
                    descriptor("withdrawn"),
                ),
            ),
        )

        assertTrue(
            InlineViewsState(withdrawn = setOf("withdrawn")).cardsFor(g).isEmpty(),
        )
    }

    @Test
    fun `cards are emitted before the group in reverse order`() {
        val item = group(
            line("l1", listOf("c1", "c2"), listOf(descriptor("c1"), descriptor("c2"))),
        )

        val rows = buildChatRows(listOf(item), InlineViewsState(), agentId = "a1")

        assertEquals(listOf("v:c2", "v:c1", "p:g"), rows.map { it.key })
    }

    @Test
    fun `an unknown agent renders no cards`() {
        val item = group(line("l1", listOf("c1"), listOf(descriptor("c1"))))

        val rows = buildChatRows(listOf(item), InlineViewsState(), agentId = null)

        assertTrue(rows.none { it is ChatRow.Card })
    }

    @Test
    fun `the same call id in two groups gives one card row`() {
        val first = ChatItem.ProgressGroup(
            key = "p:1",
            lines = listOf(line("l1", listOf("c1"), listOf(descriptor("c1")))),
        )
        val second = ChatItem.ProgressGroup(
            key = "p:2",
            lines = listOf(line("l2", listOf("c1"), listOf(descriptor("c1")))),
        )

        val rows = buildChatRows(listOf(first, second), InlineViewsState(), agentId = "a1")

        // Повтор ключа в LazyColumn — падение процесса, а не порченый кадр.
        assertEquals(1, rows.count { it is ChatRow.Card })
    }

    @Test
    fun `height clamps to 320 and to seventy percent of the screen`() {
        assertEquals(320, clampInlineViewHeight(100.0, screenHeightDp = 800))
        assertEquals(560, clampInlineViewHeight(2000.0, screenHeightDp = 800))
        assertEquals(720, clampInlineViewHeight(2000.0, screenHeightDp = 2000))
        // Ландшафт: 70% экрана меньше минимума — минимум побеждает, coerceIn не бросает.
        assertEquals(320, clampInlineViewHeight(2000.0, screenHeightDp = 300))
    }
}
