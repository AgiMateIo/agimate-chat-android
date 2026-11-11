package ru.agimate.mobile.core.realtime

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.core.network.ApiJson

/**
 * Разбор публикаций личного канала. Вынесен из клиента, чтобы таблица событий проверялась без
 * Centrifugo: конверт один, а ветки разбора — чистые функции.
 */
class RealtimeEventParserTest {

    @Test
    fun `webchat message carries tool call ids`() {
        val event = RealtimeEventParser.parse(
            RealtimeEventType.WEBCHAT_MESSAGE,
            ApiJson.parseToJsonElement("""{"sessionId":"s1","messageId":"m1","toolCallIds":["c2"]}"""),
        )

        assertEquals(listOf("c2"), (event as RealtimeEvent.Message).payload.toolCallIds)
    }

    @Test
    fun `session tool call carries descriptors`() {
        val event = RealtimeEventParser.parse(
            RealtimeEventType.SESSION_TOOL_CALL,
            ApiJson.parseToJsonElement(
                """{"sessionId":"s1","toolCallIds":["c2"],
                   "toolCalls":[{"toolCallId":"c2","name":"show_memory","connectionId":"k1",
                   "viewUri":"ui://persist-memory/memory"}]}""",
            ),
        ) as RealtimeEvent.ToolCall

        assertEquals("s1", event.payload.sessionId)
        assertEquals(listOf("c2"), event.payload.toolCallIds)
        assertEquals("ui://persist-memory/memory", event.payload.toolCalls?.single()?.viewUri)
        assertEquals("k1", event.payload.toolCalls?.single()?.connectionId)
    }

    @Test
    fun `an unresolved signal keeps toolCalls null`() {
        val event = RealtimeEventParser.parse(
            RealtimeEventType.SESSION_TOOL_CALL,
            ApiJson.parseToJsonElement("""{"sessionId":"s1","toolCallIds":["c2"],"toolCalls":null}"""),
        ) as RealtimeEvent.ToolCall

        assertNull(event.payload.toolCalls)
    }

    @Test
    fun `unknown and malformed publications are null`() {
        assertNull(RealtimeEventParser.parse("future.event", ApiJson.parseToJsonElement("""{}""")))
        assertNull(RealtimeEventParser.parse(RealtimeEventType.SESSION_TOOL_CALL, JsonNull))
        assertNull(RealtimeEventParser.parse(RealtimeEventType.SESSION_TOOL_CALL, null))
        assertNull(RealtimeEventParser.parse(null, ApiJson.parseToJsonElement("""{}""")))
    }

    @Test
    fun `known types are listed for the client`() {
        assertTrue(RealtimeEventParser.isKnown(RealtimeEventType.SESSION_TOOL_CALL))
        assertTrue(RealtimeEventParser.isKnown(RealtimeEventType.WEBCHAT_MESSAGE))
        assertTrue(!RealtimeEventParser.isKnown("future.event"))
        assertTrue(!RealtimeEventParser.isKnown(null))
    }
}
