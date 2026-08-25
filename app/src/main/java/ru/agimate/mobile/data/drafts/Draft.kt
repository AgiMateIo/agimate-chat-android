package ru.agimate.mobile.data.drafts

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.webchat.PendingAttachment

/**
 * Несобранное сообщение переписки: текст и вложения, которые человек выбрал, но не отправил.
 *
 * Это не снимок композера, а сам композер: экран его рисует, а не копирует себе. Иначе состояние
 * жило бы в двух местах, и загрузка, дошедшая после ухода с экрана, обновляла бы то из них,
 * которого уже нет.
 */
data class Draft(
    val sessionId: String,
    /** Нужен списку контактов: он знает агентов, а не переписки. */
    val agentId: String?,
    val text: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    /** Чем свежее, тем он и показывается у агента, если черновиков у того несколько. */
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()

    /** Строка для списка. У черновика из одних вложений текста нет, а молчать о нём нельзя. */
    val preview: UiText
        get() = if (text.isNotBlank()) uiText(text) else uiText(R.string.chat_attachment_generic)
}
