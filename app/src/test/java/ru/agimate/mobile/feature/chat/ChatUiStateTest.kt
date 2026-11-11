package ru.agimate.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.agimate.mobile.data.views.ToolCallDescriptor

/** Состояние чата несёт карту inline-вью рядом с лентой: экран читает её из одного `ChatUiState`. */
class ChatUiStateTest {

    @Test
    fun `inline views start empty and survive a copy`() {
        val state = ChatUiState()

        assertEquals(InlineViewsState(), state.inlineViews)

        val descriptor = ToolCallDescriptor("c1", "show_memory", "k1", "ui://v")
        val updated = state.copy(
            inlineViews = state.inlineViews.copy(overrides = mapOf("c1" to descriptor)),
        )

        assertEquals(descriptor, updated.inlineViews.overrides["c1"])
        assertEquals(InlineViewsState(), state.inlineViews)
    }
}
