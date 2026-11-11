package ru.agimate.mobile.core.realtime

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.network.InstantSerializer
import ru.agimate.mobile.data.views.ToolCallDescriptorDto
import ru.agimate.mobile.data.webchat.WebchatAttachmentDto
import ru.agimate.mobile.data.webchat.WebchatContactDto
import ru.agimate.mobile.data.webchat.WebchatSessionDto
import java.time.Instant

@Serializable
data class RealtimeEnvelope(
    val type: String? = null,
    val payload: JsonElement? = null,
)

/** Сообщение переписки веб-чата: ответ агента, строка хода работы или эхо своего. */
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
    /** Идентификаторы вызовов строки `TOOL_CALL`; дескрипторы приходят отдельным сигналом. */
    val toolCallIds: List<String>? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
)

/**
 * Сигнал `session.tool_call`: вызовы хода осели, и у них появились дескрипторы вью.
 *
 * Это именно сигнал, а не данные: `input`/`result` живут в эндпойнте вызова, страница — в
 * `views/content`. `toolCalls == null` значит «резолв не удался» — старый путь по `toolCallIds`.
 */
@Serializable
data class SessionToolCallPayloadDto(
    val sessionId: String? = null,
    val toolCallIds: List<String>? = null,
    val toolCalls: List<ToolCallDescriptorDto>? = null,
)

object RealtimeEventType {
    const val SESSION_CREATED = "session.created"
    const val SESSION_UPDATED = "session.updated"
    const val WEBCHAT_AGENT_UPDATED = "webchat.agent.updated"
    const val WEBCHAT_MESSAGE = "webchat.message"
    const val SESSION_TOOL_CALL = "session.tool_call"
}

/**
 * Что пришло в личный канал `user:{userId}` — единственный канал приложения.
 *
 * Строки сессий и контактов приходят целиком, а не разницей: их заменяют, а не сливают. Доставка
 * at-least-once и best-effort, поэтому обработчик обязан быть идемпотентным, а событие — не
 * единственный источник правды: при открытии экран всё равно берёт данные из REST.
 */
sealed interface RealtimeEvent {
    /** Строка `GET /manage/sessions/` — у веб-чата, мессенджеров и субагентов. */
    data class Session(val created: Boolean, val row: WebchatSessionDto) : RealtimeEvent

    /** Строка `GET /manage/webchat/contacts/`: счётчик в ней — уже сумма по всем перепискам. */
    data class Contact(val row: WebchatContactDto) : RealtimeEvent

    data class Message(val payload: WebchatMessagePayload) : RealtimeEvent

    /** Вызовы хода осели: у них появились дескрипторы вью. Событие приходит по всем сессиям. */
    data class ToolCall(val payload: SessionToolCallPayloadDto) : RealtimeEvent

    /**
     * Пропущенное восстановить не удалось — долгий офлайн или подписка, заведённая заново. Открытые
     * экраны перечитывают себя через REST: события за разрыв уже не придут.
     */
    data object Resync : RealtimeEvent
}

/**
 * Разбор публикации личного канала в событие. Чистые функции без Centrifugo — поэтому таблица
 * событий проверяется обычными JVM-тестами.
 *
 * Незнакомый тип — не поломка: в канал идут события экранов, которых в приложении нет, и
 * устаревшие типы. Настоящая поломка — знакомый тип с разошедшейся формой; её видно по `null`.
 */
internal object RealtimeEventParser {

    fun isKnown(type: String?): Boolean = type != null && type in KNOWN

    fun parse(type: String?, payload: JsonElement?): RealtimeEvent? {
        if (type == null || payload == null) return null
        return runCatching {
            when (type) {
                RealtimeEventType.SESSION_CREATED, RealtimeEventType.SESSION_UPDATED ->
                    RealtimeEvent.Session(
                        created = type == RealtimeEventType.SESSION_CREATED,
                        row = ApiJson.decodeFromJsonElement<WebchatSessionDto>(payload),
                    )
                RealtimeEventType.WEBCHAT_AGENT_UPDATED ->
                    RealtimeEvent.Contact(ApiJson.decodeFromJsonElement<WebchatContactDto>(payload))
                RealtimeEventType.WEBCHAT_MESSAGE ->
                    RealtimeEvent.Message(ApiJson.decodeFromJsonElement<WebchatMessagePayload>(payload))
                RealtimeEventType.SESSION_TOOL_CALL ->
                    RealtimeEvent.ToolCall(
                        ApiJson.decodeFromJsonElement<SessionToolCallPayloadDto>(payload),
                    )
                else -> null
            }
        }.getOrNull()
    }

    private val KNOWN = setOf(
        RealtimeEventType.SESSION_CREATED,
        RealtimeEventType.SESSION_UPDATED,
        RealtimeEventType.WEBCHAT_AGENT_UPDATED,
        RealtimeEventType.WEBCHAT_MESSAGE,
        RealtimeEventType.SESSION_TOOL_CALL,
    )
}

/**
 * Поток событий личного канала — узкий вход для экранов, которым нужен только он.
 *
 * Экрану вроде полного просмотра вью не нужны ни статус соединения, ни токены; зависимость от
 * конкретного [RealtimeClient] протащила бы в тест весь Centrifugo.
 */
interface RealtimeEventsSource {
    val events: SharedFlow<RealtimeEvent>
}

/** Состояние живого соединения — для полоски «связь потеряна / восстановлена». */
enum class RealtimeStatus {
    Idle, Connecting, Connected, Disconnected;

    companion object {
        /**
         * Худшее из двух состояний.
         *
         * Живое сообщение доезжает, только когда целы обе половины — и соединение, и подписка на
         * личный канал. Целый WebSocket с умершей подпиской показывал бы «на связи», пока чат
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
