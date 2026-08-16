package ru.agimate.mobile.feature.chat

import ru.agimate.mobile.data.webchat.ChatMessage

/**
 * @param messages лента после вливания, от новых к старым
 * @param applied  `false`, если сообщение оказалось повтором и ленту менять не пришлось
 */
data class LiveMerge(val messages: List<ChatMessage>, val applied: Boolean)

/**
 * Вливает живое сообщение в ленту.
 *
 * Два правила, без которых лента поедет:
 *  - доставка at-least-once, поэтому дедупликация по `messageId`: одно и то же сообщение может
 *    прийти дважды, в том числе при восстановлении подписки после разрыва;
 *  - своё сообщение возвращается эхом с `direction: "USER"` — это синхронизация между устройствами.
 *    Оптимистично показанное надо схлопнуть с эхом, а не показать дважды.
 */
fun mergeLiveMessage(current: List<ChatMessage>, incoming: ChatMessage): LiveMerge {
    val incomingId = incoming.messageId

    if (incoming.isOwn) {
        val index = current.indexOfFirst { candidate ->
            candidate.isOwn && candidate.localId != null && when {
                candidate.messageId != null -> candidate.messageId == incomingId
                // Ответ на отправку ещё не пришёл, и messageId у нас нет. Совпадение по тексту —
                // единственное, чем можно отличить своё эхо от сообщения, отправленного в этот же
                // момент с другого устройства.
                else -> candidate.text == incoming.text
            }
        }
        if (index >= 0) {
            val merged = current.toMutableList()
            merged[index] = incoming.copy(localId = current[index].localId)
            return LiveMerge(merged, applied = true)
        }
    }

    if (incomingId != null && current.any { it.messageId == incomingId }) {
        return LiveMerge(current, applied = false)
    }

    return LiveMerge(listOf(incoming) + current, applied = true)
}
