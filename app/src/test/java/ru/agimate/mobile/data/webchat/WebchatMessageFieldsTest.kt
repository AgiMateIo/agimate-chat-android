package ru.agimate.mobile.data.webchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.agimate.mobile.data.views.ToolCallDescriptorDto

/**
 * Строка истории несёт идентификаторы вызовов и, когда бэкенд их резолвил, дескрипторы вью.
 * `null` и пустой список — разные факты: `null` значит «не резолвили», `[]` — «резолвили, вью нет».
 */
class WebchatMessageFieldsTest {

    @Test
    fun `history maps tool call ids and descriptors by id`() {
        val message = ChatMessage.from(
            WebchatMessageDto(
                id = "m1",
                direction = "AGENT",
                stream = "progress",
                toolCallIds = listOf("c1", "c2"),
                toolCalls = listOf(
                    ToolCallDescriptorDto(
                        toolCallId = "c2",
                        name = "show_memory",
                        connectionId = "k1",
                        viewUri = "ui://persist-memory/memory",
                    ),
                ),
            ),
        )

        assertEquals(listOf("c1", "c2"), message.toolCallIds)
        assertEquals("c2", message.toolCalls?.single()?.toolCallId)
        assertEquals("show_memory", message.toolCalls?.single()?.name)
        assertEquals("k1", message.toolCalls?.single()?.connectionId)
        assertEquals("ui://persist-memory/memory", message.toolCalls?.single()?.viewUri)
    }

    @Test
    fun `absent fields stay null and an empty descriptor list stays empty`() {
        assertNull(ChatMessage.from(WebchatMessageDto(id = "m1")).toolCallIds)
        assertNull(ChatMessage.from(WebchatMessageDto(id = "m1")).toolCalls)

        val resolved = ChatMessage.from(
            WebchatMessageDto(id = "m1", toolCallIds = listOf("c1"), toolCalls = emptyList()),
        )
        assertEquals(listOf("c1"), resolved.toolCallIds)
        assertEquals(emptyList<Any>(), resolved.toolCalls)
    }
}
