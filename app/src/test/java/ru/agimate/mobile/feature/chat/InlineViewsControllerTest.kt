package ru.agimate.mobile.feature.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.core.realtime.SessionToolCallPayloadDto
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.views.AgentViewContent
import ru.agimate.mobile.data.views.SessionToolCall
import ru.agimate.mobile.data.views.ToolCallDescriptor
import ru.agimate.mobile.data.views.ToolCallDescriptorDto
import ru.agimate.mobile.data.views.ToolCallRefresh
import ru.agimate.mobile.data.views.ToolCallView
import ru.agimate.mobile.data.views.ToolCallViewSource
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import java.time.Instant

/**
 * Состояние карточек: один источник истины для раскрытия, гонок и `404`-политики.
 *
 * Проверяется без Android: загрузчик подменён, временем управляет `runTest`. Главные обещания —
 * до тапа запросов нет, поздний ответ не воскрешает карточку, сигнал главнее истории.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineViewsControllerTest {

    private val content = AgentViewContent(html = "<html>view</html>", mimeType = "text/html")

    private fun descriptor(id: String = "c1", view: String? = "ui://v") = ToolCallDescriptor(
        toolCallId = id,
        name = "show_$id",
        connectionId = "k1",
        viewUri = view,
    )

    private fun call(
        id: String = "c1",
        view: String? = "ui://v",
        result: JsonObject? = buildJsonObject { put("isError", false) },
    ) = SessionToolCall(
        toolCallId = id,
        name = "show_$id",
        connectionId = "k1",
        viewUri = view,
        input = buildJsonObject { },
        result = result,
    )

    private fun historyLine(
        ids: List<String>,
        calls: List<ToolCallDescriptor>? = null,
    ) = ChatMessage(
        rowId = "l1",
        messageId = "m1",
        direction = MessageDirection.AGENT,
        stream = MessageStream.PROGRESS,
        text = "🔧",
        attachments = emptyList(),
        createdAt = Instant.EPOCH,
        toolCallIds = ids,
        toolCalls = calls,
    )

    private fun group(vararg lines: ChatMessage) =
        ChatItem.ProgressGroup(key = "p:g", lines = lines.toList())

    private fun signal(vararg descriptors: ToolCallDescriptor) = SessionToolCallPayloadDto(
        sessionId = "s1",
        toolCallIds = descriptors.map { it.toolCallId },
        toolCalls = descriptors.map {
            ToolCallDescriptorDto(it.toolCallId, it.name, it.connectionId, it.viewUri)
        },
    )

    private class FakeSource : ToolCallViewSource {
        val loads = mutableListOf<ToolCallDescriptor>()
        val refreshes = mutableListOf<String>()
        var nextLoad = CompletableDeferred<ToolCallView>()
        var nextRefresh = CompletableDeferred<ToolCallRefresh>()

        override suspend fun load(
            agentId: String,
            sessionId: String,
            descriptor: ToolCallDescriptor,
        ): ToolCallView {
            loads += descriptor
            return nextLoad.await()
        }

        override suspend fun load(
            agentId: String,
            sessionId: String,
            toolCallId: String,
        ): ToolCallView = error("the controller uses the descriptor overload")

        override suspend fun refreshCall(
            sessionId: String,
            toolCallId: String,
        ): ToolCallRefresh {
            refreshes += toolCallId
            return nextRefresh.await()
        }
    }

    @Test
    fun `nothing is loaded before expansion`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")

        controller.onSignal(signal(descriptor()))
        controller.onSessionMessage()
        advanceUntilIdle()

        assertTrue(source.loads.isEmpty())
        assertTrue(source.refreshes.isEmpty())
    }

    @Test
    fun `history without descriptors resolves each id once and enables the card`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextRefresh.complete(ToolCallRefresh.Updated(call()))

        controller.onHistory(listOf(historyLine(listOf("c1"))))
        advanceUntilIdle()

        assertEquals(listOf("c1"), source.refreshes)
        assertEquals("ui://v", controller.state.value.overrides["c1"]?.viewUri)
        assertEquals(
            listOf("c1"),
            controller.state.value.cardsFor(group(historyLine(listOf("c1")))).map { it.toolCallId },
        )

        controller.onHistory(listOf(historyLine(listOf("c1"))))
        advanceUntilIdle()
        assertEquals("one request per id", 1, source.refreshes.size)
    }

    @Test
    fun `history fallback waits for the agent id`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        source.nextRefresh.complete(ToolCallRefresh.Updated(call()))

        controller.onHistory(listOf(historyLine(listOf("c1"))))
        advanceUntilIdle()
        assertTrue(source.refreshes.isEmpty())

        controller.setAgentId("a1")
        advanceUntilIdle()

        assertEquals(listOf("c1"), source.refreshes)
    }

    @Test
    fun `history fallback without a view hides the card`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextRefresh.complete(ToolCallRefresh.Gone)

        controller.onHistory(listOf(historyLine(listOf("c1"))))
        advanceUntilIdle()

        assertNull(controller.state.value.overrides["c1"]?.viewUri)
        assertTrue(controller.state.value.cardsFor(group(historyLine(listOf("c1")))).isEmpty())
    }

    @Test
    fun `history with descriptors makes no fallback requests`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")

        controller.onHistory(listOf(historyLine(listOf("c1"), calls = listOf(descriptor()))))
        advanceUntilIdle()

        assertTrue(source.refreshes.isEmpty())
        assertEquals(
            listOf("c1"),
            controller.state.value.cardsFor(
                group(historyLine(listOf("c1"), calls = listOf(descriptor()))),
            ).map { it.toolCallId },
        )
    }

    @Test
    fun `a resolved history line replaces a stale fallback descriptor`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextRefresh.complete(ToolCallRefresh.Updated(call(view = "ui://old")))
        controller.onHistory(listOf(historyLine(listOf("c1"))))
        advanceUntilIdle()
        assertEquals("ui://old", controller.state.value.overrides["c1"]?.viewUri)

        val resolvedLine = historyLine(listOf("c1"), calls = listOf(descriptor(view = "ui://new")))
        controller.onHistory(listOf(resolvedLine))
        advanceUntilIdle()

        // Резолв истории свежее fallback-ответа: старый дескриптор не должен перекрыть новый.
        assertNull(controller.state.value.overrides["c1"])
        assertEquals(
            "ui://new",
            controller.state.value.cardsFor(group(resolvedLine)).single().descriptor.viewUri,
        )
    }

    @Test
    fun `a signal wins over a late history fallback`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        controller.onHistory(listOf(historyLine(listOf("c1"))))
        advanceUntilIdle()

        controller.onSignal(signal(descriptor(view = "ui://signal")))
        source.nextRefresh.complete(ToolCallRefresh.Updated(call(view = "ui://history")))
        advanceUntilIdle()

        assertEquals("ui://signal", controller.state.value.overrides["c1"]?.viewUri)
    }

    @Test
    fun `expand loads and reaches Ready`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))

        controller.expand(descriptor())
        assertEquals(InlineViewPhase.Loading, controller.state.value.cards["c1"]?.phase)

        advanceUntilIdle()

        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Ready)
        assertEquals(listOf("c1"), source.loads.map { it.toolCallId })
    }

    @Test
    fun `a null result stays AwaitingResult and the next message refreshes it`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(result = null), content))
        controller.expand(descriptor())
        advanceUntilIdle()
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.AwaitingResult)

        source.nextRefresh.complete(
            ToolCallRefresh.Updated(call(result = buildJsonObject { put("isError", false) })),
        )
        controller.onSessionMessage()
        advanceUntilIdle()

        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Ready)
        assertEquals(listOf("c1"), source.refreshes)
    }

    @Test
    fun `collapse cancels a late response and frees the content`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        controller.expand(descriptor())
        advanceUntilIdle()
        assertEquals(InlineViewPhase.Loading, controller.state.value.cards["c1"]?.phase)

        controller.collapse("c1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        advanceUntilIdle()

        assertNull(controller.state.value.cards["c1"])
    }

    @Test
    fun `page 404 withdraws the card and a later signal revives it`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.PageGone)
        controller.expand(descriptor())
        advanceUntilIdle()

        assertNull(controller.state.value.cards["c1"])
        assertTrue("c1" in controller.state.value.withdrawn)

        controller.onSignal(signal(descriptor()))
        assertTrue("c1" !in controller.state.value.withdrawn)

        source.nextLoad = CompletableDeferred()
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.expand(descriptor())
        advanceUntilIdle()
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Ready)
    }

    @Test
    fun `call 404 leaves the trigger in Unavailable`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.NoView)
        controller.expand(descriptor())
        advanceUntilIdle()

        assertEquals(InlineViewPhase.Unavailable, controller.state.value.cards["c1"]?.phase)
    }

    @Test
    fun `page 502 is Failed and retry loads again`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.PageFailed(uiText("bad page")))
        controller.expand(descriptor())
        advanceUntilIdle()
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Failed)

        source.nextLoad = CompletableDeferred()
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.retry(descriptor())
        advanceUntilIdle()

        assertEquals(2, source.loads.size)
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Ready)
    }

    @Test
    fun `a signal without a view discards the card`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.expand(descriptor())
        advanceUntilIdle()
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Ready)

        controller.onSignal(signal(descriptor(view = null)))

        assertNull(controller.state.value.cards["c1"])
        assertNull(controller.state.value.overrides["c1"]?.viewUri)
    }

    @Test
    fun `a duplicate signal does not start a second load`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        controller.expand(descriptor())
        advanceUntilIdle()

        controller.onSignal(signal(descriptor()))
        controller.onSignal(signal(descriptor()))
        advanceUntilIdle()

        assertEquals(1, source.loads.size)
        assertTrue(source.refreshes.isEmpty())
        // Загрузка осталась висеть на незавершённом deferred — гасим её до конца теста.
        controller.clear()
    }

    @Test
    fun `expand without an agent id does nothing`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)

        controller.expand(descriptor())
        advanceUntilIdle()

        assertTrue(source.loads.isEmpty())
        assertTrue(controller.state.value.cards.isEmpty())
    }

    @Test
    fun `renderer gone turns a ready card into Failed`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.expand(descriptor())
        advanceUntilIdle()

        controller.onRendererGone("c1")

        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Failed)
    }

    @Test
    fun `a signal with a changed address reloads an expanded card`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.expand(descriptor())
        advanceUntilIdle()

        source.nextLoad = CompletableDeferred()
        controller.onSignal(signal(descriptor(view = "ui://new")))
        advanceUntilIdle()

        assertEquals(2, source.loads.size)
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Loading)
        controller.clear()
    }

    @Test
    fun `a signal without a connection discards the card`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.expand(descriptor())
        advanceUntilIdle()
        assertTrue(controller.state.value.cards.getValue("c1").phase is InlineViewPhase.Ready)

        controller.onSignal(signal(descriptor().copy(connectionId = null)))

        assertNull(controller.state.value.cards["c1"])
    }

    @Test
    fun `a changed address during loading restarts the load`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        controller.expand(descriptor())
        advanceUntilIdle()
        assertEquals(InlineViewPhase.Loading, controller.state.value.cards["c1"]?.phase)

        source.nextLoad = CompletableDeferred()
        controller.onSignal(signal(descriptor(view = "ui://new")))
        advanceUntilIdle()

        assertEquals(2, source.loads.size)
        assertEquals("ui://new", source.loads.last().viewUri)
        assertEquals(InlineViewPhase.Loading, controller.state.value.cards["c1"]?.phase)
        controller.clear()
    }

    @Test
    fun `a repeated signal on a ready card refreshes the call`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(), content))
        controller.expand(descriptor())
        advanceUntilIdle()

        source.nextRefresh.complete(ToolCallRefresh.Failed)
        controller.onSignal(signal(descriptor()))
        advanceUntilIdle()

        assertEquals(listOf("c1"), source.refreshes)
        assertEquals(1, source.loads.size)
    }

    @Test
    fun `refresh Gone makes an open card Unavailable`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        source.nextLoad.complete(ToolCallView.Ready(call(result = null), content))
        controller.expand(descriptor())
        advanceUntilIdle()

        source.nextRefresh.complete(ToolCallRefresh.Gone)
        controller.onSessionMessage()
        advanceUntilIdle()

        assertEquals(InlineViewPhase.Unavailable, controller.state.value.cards["c1"]?.phase)
    }

    @Test
    fun `clear resets cards and overrides`() = runTest {
        val source = FakeSource()
        val controller = InlineViewsController("s1", source, this)
        controller.setAgentId("a1")
        controller.onSignal(signal(descriptor()))

        controller.clear()

        assertEquals(InlineViewsState(), controller.state.value)
    }
}
