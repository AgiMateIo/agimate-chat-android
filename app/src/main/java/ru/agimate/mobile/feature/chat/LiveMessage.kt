package ru.agimate.mobile.feature.chat

import ru.agimate.mobile.core.realtime.WebchatMessagePayload
import ru.agimate.mobile.data.webchat.Attachment
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import java.time.Instant

/**
 * Живое сообщение из личного канала в строку ленты.
 *
 * `toolCallIds` обязаны доехать: по ним лента находит карточки вью. Потеря поля здесь не видна в
 * пузыре, но оставляет `session.tool_call` без адресата — карточки не появятся до перезагрузки
 * истории.
 */
internal fun liveMessage(
    payload: WebchatMessagePayload,
    now: Instant = Instant.now(),
): ChatMessage = ChatMessage(
    rowId = null,
    messageId = payload.messageId,
    direction = MessageDirection.parse(payload.direction),
    stream = MessageStream.parse(payload.stream),
    text = payload.text,
    attachments = payload.parts.orEmpty().map(Attachment::from),
    createdAt = payload.createdAt ?: now,
    toolCallIds = payload.toolCallIds,
)
