package ru.agimate.mobile.data.webchat

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import java.time.Instant
import java.util.UUID

/** Превью последнего сообщения для строки списка. */
data class MessagePreview(
    val text: String?,
    val fromAgent: Boolean,
    val hasAttachments: Boolean,
    val createdAt: Instant?,
) {
    /**
     * Что показать в строке. Пустой текст при вложении — не пустая строка: сообщение было только
     * из файлов.
     */
    val displayText: UiText
        get() = when {
            !text.isNullOrBlank() -> uiText(text)
            hasAttachments -> uiText(R.string.chat_attachment_generic)
            else -> uiText("")
        }

    companion object {
        fun from(dto: WebchatLastMessageDto?): MessagePreview? = dto?.let {
            MessagePreview(
                text = it.text,
                fromAgent = MessageDirection.parse(it.direction) == MessageDirection.AGENT,
                hasAttachments = it.hasAttachments,
                createdAt = it.createdAt,
            )
        }
    }
}

/** Агент как контакт: сам агент плюс состояние переписки с ним. */
data class Contact(
    val agentId: String,
    val name: String,
    val description: String?,
    /** Выключенный агент: историю показываем, отправку запрещаем. */
    val enabled: Boolean,
    val unreadCount: Long,
    val preview: MessagePreview?,
    /** Переписка, из которой взято превью, — её и открывать по тапу. */
    val lastSessionId: String?,
    val lastActivityAt: Instant?,
    val isRunning: Boolean,
) {
    companion object {
        fun from(dto: WebchatContactDto) = Contact(
            agentId = dto.agentId,
            name = dto.name,
            description = dto.description,
            enabled = dto.enabled,
            unreadCount = dto.unreadCount,
            preview = MessagePreview.from(dto.lastMessage),
            lastSessionId = dto.lastSessionId,
            lastActivityAt = dto.lastActivityAt,
            isRunning = dto.isRunning,
        )
    }
}

data class ChatSession(
    val sessionId: String,
    val agentId: String?,
    val title: String?,
    val lastMessageAt: Instant?,
    val closedAt: Instant?,
    val createdAt: Instant?,
    val unreadCount: Long,
    val preview: MessagePreview?,
    val isRunning: Boolean,
) {
    val isClosed: Boolean get() = closedAt != null

    companion object {
        fun from(dto: WebchatSessionDto) = ChatSession(
            sessionId = dto.sessionId,
            agentId = dto.agentId,
            title = dto.title,
            lastMessageAt = dto.lastMessageAt,
            closedAt = dto.closedAt,
            createdAt = dto.createdAt,
            unreadCount = dto.unreadCount,
            preview = MessagePreview.from(dto.lastMessage),
            isRunning = dto.isRunning,
        )
    }
}

data class Attachment(
    val fileId: String?,
    val mime: String?,
    val size: Long?,
    val name: String?,
    /** Подписанный адрес как его отдал сервер: относительный или абсолютный. См. OriginProvider.fileUrl. */
    val url: String?,
) {
    val isImage: Boolean get() = mime?.startsWith("image/") == true

    companion object {
        fun from(dto: WebchatAttachmentDto) = Attachment(
            fileId = dto.fileId,
            mime = dto.mime,
            size = dto.size,
            name = dto.name,
            url = dto.url,
        )
    }
}

/**
 * Сообщение переписки.
 *
 * @param rowId идентификатор строки — по нему считается порядок и им отмечают прочтение
 * @param messageId ключ доставки — по нему дедуплицируются real-time события, порядка не несёт
 */
data class ChatMessage(
    val rowId: String?,
    val messageId: String?,
    val direction: MessageDirection,
    val stream: MessageStream,
    val text: String?,
    val attachments: List<Attachment>,
    val createdAt: Instant?,
    /** Оптимистично показанное своё сообщение, ещё не подтверждённое сервером. */
    val pending: Boolean = false,
    /** Отправка не удалась — показать повтор. */
    val failed: Boolean = false,
    /** Локальный ключ оптимистичного сообщения: по нему схлопываем эхо. */
    val localId: String? = null,
    /**
     * Ключ элемента списка: у подтверждённых — id строки, у оптимистичных — локальный, у живых —
     * `messageId`.
     *
     * Считается один раз при создании, а не свойством-геттером, и потому обязан быть непустым:
     * два одинаковых ключа роняют LazyColumn, а все три идентификатора объявлены необязательными.
     * Последняя ступень — сгенерированный id: сообщение без единого идентификатора всё равно
     * останется различимым.
     *
     * `copy` ключ не пересчитывает, и это то, что нужно: правки сообщения (пришёл `messageId`,
     * снялся `pending`) не должны выглядеть для списка как другой элемент. Единственное место, где
     * ключ передают явно, — схлопывание эха в [ru.agimate.mobile.feature.chat.mergeLiveMessage].
     */
    val key: String = rowId ?: localId ?: messageId ?: UUID.randomUUID().toString(),
) {
    val isOwn: Boolean get() = direction == MessageDirection.USER

    companion object {
        fun from(dto: WebchatMessageDto) = ChatMessage(
            rowId = dto.id,
            messageId = dto.messageId,
            direction = MessageDirection.parse(dto.direction),
            stream = MessageStream.parse(dto.stream),
            text = dto.text,
            attachments = dto.parts.orEmpty().map(Attachment::from),
            createdAt = dto.createdAt,
        )
    }
}
