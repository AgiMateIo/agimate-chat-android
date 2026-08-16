package ru.agimate.mobile.data.webchat

import kotlinx.serialization.Serializable
import ru.agimate.mobile.core.network.InstantSerializer
import java.time.Instant

/** Поток ответа агента. У своих сообщений `stream` = null. */
enum class MessageStream {
    /** Промежуточные шаги работы: «читаю таблицу», «ищу письмо». Их бывает много подряд. */
    PROGRESS,

    /** Собственно ответ, markdown. */
    ANSWER,

    /** Ошибка вместо ответа. */
    ERROR,

    /** Своё сообщение либо неизвестный сервер-сайд поток. */
    NONE;

    /** Непрочитанным считается сообщение агента со `stream` = answer или error. */
    val countsAsUnread: Boolean get() = this == ANSWER || this == ERROR

    companion object {
        fun parse(raw: String?): MessageStream = when (raw?.lowercase()) {
            "progress" -> PROGRESS
            "answer" -> ANSWER
            "error" -> ERROR
            else -> NONE
        }
    }
}

enum class MessageDirection {
    USER, AGENT;

    companion object {
        fun parse(raw: String?): MessageDirection =
            if (raw.equals("USER", ignoreCase = true)) USER else AGENT
    }
}

@Serializable
data class WebchatLastMessageDto(
    /** Обрезан до 160 символов сервером; `null`, когда сообщение было только из вложений. */
    val text: String? = null,
    val direction: String? = null,
    val hasAttachments: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
)

@Serializable
data class WebchatContactDto(
    val agentId: String,
    val name: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
    /** Сумма по всем перепискам этого агента. */
    val unreadCount: Long = 0,
    val lastMessage: WebchatLastMessageDto? = null,
    /** Переписка, из которой взято превью; `null` у агента, которому ещё не писали. */
    val lastSessionId: String? = null,
    @Serializable(with = InstantSerializer::class)
    val lastActivityAt: Instant? = null,
    /** Ключ называется именно так — не `running`. */
    val isRunning: Boolean = false,
)

@Serializable
data class WebchatSessionDto(
    val sessionId: String,
    val channelId: String? = null,
    val agentId: String? = null,
    /** Генерируется из первого сообщения. */
    val title: String? = null,
    @Serializable(with = InstantSerializer::class)
    val lastMessageAt: Instant? = null,
    /** Закрытые переписки приходят вместе с открытыми — фильтра нет, различать по этому полю. */
    @Serializable(with = InstantSerializer::class)
    val closedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
    val unreadCount: Long = 0,
    val lastMessage: WebchatLastMessageDto? = null,
    val isRunning: Boolean = false,
)

@Serializable
data class WebchatAttachmentDto(
    val type: String? = null,
    val fileId: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val name: String? = null,
    /**
     * Относительный подписанный адрес, живёт 15 минут и приходит **без** префикса `/control`.
     * Полный адрес собирает [ru.agimate.mobile.core.network.OriginProvider.fileUrl].
     */
    val url: String? = null,
)

@Serializable
data class WebchatMessageDto(
    /** Идентификатор строки: по нему считается порядок и им отмечают прочтение. */
    val id: String,
    /** Ключ доставки: по нему дедуплицируются real-time события. Порядка не несёт. */
    val messageId: String? = null,
    val direction: String? = null,
    val stream: String? = null,
    val text: String? = null,
    val parts: List<WebchatAttachmentDto>? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
)

@Serializable
data class StartSessionRequest(val agentId: String)

@Serializable
data class SendMessageRequest(
    val text: String? = null,
    val parts: List<AttachmentRef>? = null,
)

@Serializable
data class AttachmentRef(val fileId: String)

@Serializable
data class SendMessageResponseDto(
    val sessionId: String,
    val messageId: String,
)

/** Тело необязательно: без него сессия помечается прочитанной до конца. */
@Serializable
data class MarkReadRequest(val lastReadMessageId: String? = null)

@Serializable
data class WebchatFileDto(
    val fileId: String,
    val mime: String? = null,
    val size: Long = 0,
    val name: String? = null,
    @Serializable(with = InstantSerializer::class)
    val expiresAt: Instant? = null,
)

@Serializable
data class CentrifugoTokenDto(
    val connectionToken: String,
    val subscriptionToken: String,
    val channel: String,
    /** Брать из ответа, не зашивать. */
    val wsUrl: String,
)

@Serializable
data class CancelSessionDto(
    val sessionId: String? = null,
    /** Сколько запусков попало под отмену. */
    val cancelled: Int = 0,
)
