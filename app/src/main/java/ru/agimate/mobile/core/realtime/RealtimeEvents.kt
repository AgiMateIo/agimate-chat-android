package ru.agimate.mobile.core.realtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import ru.agimate.mobile.core.network.InstantSerializer
import ru.agimate.mobile.data.webchat.WebchatAttachmentDto
import java.time.Instant

@Serializable
data class RealtimeEnvelope(
    val type: String? = null,
    val payload: JsonElement? = null,
)

/**
 * Тонкое событие личного канала `user:{userId}` — поднимает бейдж, пока чат не открыт.
 *
 * Приходит только для `answer` и `error`; для `progress` и для эха своих сообщений — нет. Событие
 * best-effort: при сбое публикации теряется, и счётчик чинится следующим листингом. Единственным
 * источником правды для бейджа его делать нельзя.
 */
@Serializable
data class WebchatActivityPayload(
    val agentId: String? = null,
    val sessionId: String? = null,
    val messageId: String? = null,
    val stream: String? = null,
    val preview: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
)

/** Само сообщение из канала переписки `webchat:{sessionId}`. */
@Serializable
data class WebchatMessagePayload(
    val sessionId: String? = null,
    val channelId: String? = null,
    val agentId: String? = null,
    val messageId: String? = null,
    val direction: String? = null,
    val stream: String? = null,
    val text: String? = null,
    val parts: List<WebchatAttachmentDto>? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
)

object RealtimeEventType {
    const val MESSAGE = "webchat_message"
    const val ACTIVITY = "webchat_activity"
}

/** Состояние живого соединения — для полоски «связь потеряна / восстановлена». */
enum class RealtimeStatus { Idle, Connecting, Connected, Disconnected }
