package ru.agimate.mobile.core.push

/**
 * Уведомление о сообщении агента.
 *
 * Приходит только полем `data`, без notification-блока: с ним уведомление рисует сам SDK, а нам
 * нужно решать самим — открытую переписку уведомлять незачем, сообщение и так приедет в ленту.
 */
data class PushMessage(
    val sessionId: String,
    val agentId: String?,
    /**
     * Имя агента из пуша. `null` — сервер его не прислал; чем заменить, решает показывающий:
     * запасное имя это перевод, а разбору пуша про язык знать неоткуда.
     */
    val agentName: String?,
    val messageId: String?,
    val preview: String?,
) {
    /**
     * Куда ведёт тап. Отдельно от самого сообщения: навигации нужен только адрес переписки.
     *
     * Имя подставляет вызывающий: у шапки чата, открытого из шторки, и у заголовка уведомления оно
     * должно быть одно и то же.
     */
    fun target(agentName: String) =
        PushChatTarget(sessionId = sessionId, agentId = agentId, agentName = agentName)

    companion object {
        /** Тип события. Совпадает с тем, что бэкенд публикует в Centrifugo. */
        const val TYPE_WEBCHAT_MESSAGE = "webchat_message"

        fun parse(data: Map<String, String>): PushMessage? {
            if (data["type"] != TYPE_WEBCHAT_MESSAGE) return null
            val sessionId = data["sessionId"]?.takeIf { it.isNotBlank() } ?: return null
            return PushMessage(
                sessionId = sessionId,
                agentId = data["agentId"]?.takeIf { it.isNotBlank() },
                agentName = data["agentName"]?.takeIf { it.isNotBlank() },
                messageId = data["messageId"]?.takeIf { it.isNotBlank() },
                preview = data["preview"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Переписка, которую надо открыть. Ровно то, что нужно маршруту чата. */
data class PushChatTarget(
    val sessionId: String,
    val agentId: String?,
    val agentName: String,
)

/**
 * Показывать ли уведомление.
 *
 * Молчим в единственном случае: человек прямо сейчас смотрит именно эту переписку. Свёрнутое
 * приложение считается закрытым, даже если экран чата остался в стеке — `ChatViewModel` живёт
 * дальше и держит переписку «открытой», но уведомление в этот момент как раз и нужно.
 */
fun shouldShowPush(
    message: PushMessage,
    openSessionId: String?,
    appVisible: Boolean,
): Boolean = !(appVisible && openSessionId == message.sessionId)
