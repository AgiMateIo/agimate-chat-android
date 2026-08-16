package ru.agimate.mobile.feature.chat

import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageStream
import java.time.ZoneId

/** Элемент ленты. Лента хранится «от новых к старым» — так же, как её отдаёт сервер. */
sealed interface ChatItem {
    val key: String

    data class Bubble(val message: ChatMessage) : ChatItem {
        override val key: String get() = "m:${message.key}"
    }

    /**
     * Свёрнутые промежуточные шаги. Их бывает десяток подряд на один ответ, и пузырями они
     * превращают ленту в лог — но выбросить их нельзя: человек по ним понимает, чем агент занят.
     */
    data class ProgressGroup(override val key: String, val lines: List<ChatMessage>) : ChatItem

    data class DaySeparator(override val key: String, val label: String) : ChatItem
}

/**
 * Собирает ленту: пузыри, свёрнутые progress-группы и разделители дней.
 *
 * Вход — сообщения от новых к старым (как приходят с сервера и как рисует LazyColumn с
 * `reverseLayout`). Разделитель дня встаёт после последнего сообщения этого дня в массиве, потому
 * что при перевёрнутой отрисовке «после» означает «выше».
 */
fun buildChatItems(
    messages: List<ChatMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var index = 0

    while (index < messages.size) {
        val message = messages[index]

        if (message.stream == MessageStream.PROGRESS) {
            val group = mutableListOf<ChatMessage>()
            while (index < messages.size && messages[index].stream == MessageStream.PROGRESS) {
                group += messages[index]
                index++
            }
            items += ChatItem.ProgressGroup(
                key = "p:${group.first().key}",
                // Внутри группы — в хронологическом порядке: их читают сверху вниз.
                lines = group.reversed(),
            )
        } else {
            items += ChatItem.Bubble(message)
            index++
        }

        val anchor = messages.getOrNull(index - 1)
        val newest = anchor?.createdAt
        if (anchor != null && newest != null) {
            val older = messages.getOrNull(index)?.createdAt
            val startsNewDay = older == null ||
                older.atZone(zone).toLocalDate() != newest.atZone(zone).toLocalDate()
            if (startsNewDay) {
                items += ChatItem.DaySeparator(
                    // Ключ — по сообщению, над которым разделитель встал, а не по дате. Сообщение
                    // без `createdAt` разрывает ленту на «разные дни» посередине, и одна и та же
                    // дата даёт два разделителя, а на повторившемся ключе LazyColumn падает.
                    key = "d:${anchor.key}",
                    label = TimeFormat.daySeparator(newest, zone),
                )
            }
        }
    }

    return items
}
