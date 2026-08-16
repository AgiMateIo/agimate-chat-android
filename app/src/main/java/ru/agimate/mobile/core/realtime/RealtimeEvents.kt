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

/**
 * Что происходит в канале переписки.
 *
 * Состояние подписки едет тем же потоком, что и сообщения, не отдельным: подписка живёт ровно
 * столько, сколько её читают, и отдельный поток состояния пришлось бы считать вторым читателем.
 */
sealed interface SessionEvent {
    data class Message(val payload: WebchatMessagePayload) : SessionEvent

    data class Status(val status: RealtimeStatus) : SessionEvent
}

/** Состояние живого соединения — для полоски «связь потеряна / восстановлена». */
enum class RealtimeStatus {
    Idle, Connecting, Connected, Disconnected;

    companion object {
        /**
         * Худшее из двух состояний.
         *
         * Живое сообщение доезжает, только когда целы обе половины — и соединение, и подписка на
         * канал переписки. Целый WebSocket с умершей подпиской показывал бы «на связи», пока чат
         * молчит, — а это ровно тот случай, который надо видеть.
         *
         * `Idle` получается, только когда **обе** половины ещё не отчитались. Одна известная и одна
         * неизвестная — это «подключаюсь», а не «ничего не начиналось»: экран считает всё, кроме
         * `Connected`, поводом завести таймер потери связи, и `Idle` от живой половины показывал бы
         * «связь потеряна» поверх исправного соединения.
         */
        fun worseOf(a: RealtimeStatus, b: RealtimeStatus): RealtimeStatus = when {
            a == Disconnected || b == Disconnected -> Disconnected
            a == Idle && b == Idle -> Idle
            a == Connected && b == Connected -> Connected
            else -> Connecting
        }
    }
}
