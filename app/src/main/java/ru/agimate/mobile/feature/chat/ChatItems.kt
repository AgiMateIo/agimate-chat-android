package ru.agimate.mobile.feature.chat

import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.text.UiText
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

    data class DaySeparator(override val key: String, val label: UiText) : ChatItem
}

/**
 * Собирает ленту: пузыри, свёрнутые progress-группы и разделители дней.
 *
 * Вход — сообщения от новых к старым (как приходят с сервера и как рисует LazyColumn с
 * `reverseLayout`). Разделитель дня встаёт после последнего сообщения этого дня в массиве, потому
 * что при перевёрнутой отрисовке «после» означает «выше».
 *
 * Повторившийся ключ здесь отбрасывается, и это не забота о красоте списка: LazyColumn на двух
 * одинаковых ключах бросает исключение в главном потоке, то есть роняет приложение. Дубликаты
 * убираются в источнике — при склейке страниц и живых событий, — но лента собирается из трёх
 * потоков сразу, и цена пропущенного случая слишком велика, чтобы полагаться на одну проверку.
 * Отбрасываем именно повтор: он всегда второе появление того же сообщения, и показывать его дважды
 * всё равно было бы неправильно.
 */
fun buildChatItems(
    source: List<ChatMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatItem> {
    val messages = source.distinctByKey()
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

        // Индекс уже сдвинут за только что выпущенный элемент, так что предыдущий существует всегда.
        val anchor = messages[index - 1]
        val newest = anchor.createdAt
        if (newest != null) {
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

/**
 * Строка ленты после разворачивания групп в карточки.
 *
 * `ChatItem` остаётся про сообщения; карточка — не сообщение, а поверхность, привязанная к строке
 * вызова. Держать её отдельным типом дешевле, чем учить `buildChatItems` знать про дескрипторы.
 */
sealed interface ChatRow {
    val key: String

    data class Message(val item: ChatItem) : ChatRow {
        override val key: String get() = item.key
    }

    data class Card(val ref: InlineCardRef) : ChatRow {
        override val key: String get() = "v:${ref.toolCallId}"
    }
}

/**
 * Разворачивает ленту в строки для `LazyColumn`.
 *
 * Список перевёрнут: меньший индекс — ниже на экране. Карточка «под группой» обязана стоять
 * **перед** ней в списке, а внутри блока — в обратном порядке, чтобы сверху вниз они читались как
 * `toolCallIds` строки. Без агента карточек нет: страницу нечем адресовать.
 */
fun buildChatRows(
    items: List<ChatItem>,
    inline: InlineViewsState,
    agentId: String?,
): List<ChatRow> {
    if (agentId == null) return items.map(ChatRow::Message)

    // Ключ карточки — `toolCallId`, общий на всю ленту: повтор в двух группах уронил бы
    // LazyColumn, поэтому карточка показывается один раз.
    val seenCards = HashSet<String>()
    return items.flatMap { item ->
        if (item is ChatItem.ProgressGroup) {
            val cards = inline.cardsFor(item).asReversed().mapNotNull { ref ->
                if (seenCards.add(ref.toolCallId)) ChatRow.Card(ref) else null
            }
            cards + ChatRow.Message(item)
        } else {
            listOf(ChatRow.Message(item))
        }
    }
}

/**
 * Первое вхождение каждого ключа. Дешёвый путь — когда дублей нет — не создаёт нового списка.
 */
private fun List<ChatMessage>.distinctByKey(): List<ChatMessage> {
    val seen = HashSet<String>(size)
    var duplicates = false
    for (message in this) {
        if (!seen.add(message.key)) {
            duplicates = true
            break
        }
    }
    if (!duplicates) return this

    val unique = HashSet<String>(size)
    return filter { unique.add(it.key) }
}
