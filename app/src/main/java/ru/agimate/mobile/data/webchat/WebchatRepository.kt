package ru.agimate.mobile.data.webchat

import ru.agimate.mobile.core.network.PageEnvelope
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.unwrap
import javax.inject.Inject
import javax.inject.Singleton

/** Одна страница чего-либо плюс признак «дальше ничего нет». */
data class Paged<T>(val items: List<T>, val isLast: Boolean, val totalElements: Long)

@Singleton
class WebchatRepository @Inject constructor(
    private val api: WebchatApi,
) {
    suspend fun contacts(page: Int, size: Int = PAGE_SIZE): Paged<Contact> =
        apiCall { api.contacts(page, size) }
            .unwrap("список контактов")
            .toPaged(Contact::from)

    /**
     * Переписки агента. Фильтр по коннектору обязателен: без него ресурс отдаёт вообще все сессии
     * пользователя, включая мессенджеры и поток событий подключения без канала.
     */
    suspend fun sessions(agentId: String, page: Int, size: Int = PAGE_SIZE): Paged<ChatSession> =
        apiCall { api.sessions(agentId, CONNECTOR_WEBCHAT, page, size) }
            .unwrap("переписки агента")
            .toPaged(ChatSession::from)

    /** Состояние одной переписки: закрыта ли, работает ли агент, как называется. */
    suspend fun session(sessionId: String): ChatSession =
        ChatSession.from(apiCall { api.session(sessionId) }.unwrap("переписка"))

    /** Первая страница — конец переписки; листание вверх это `page + 1`. */
    suspend fun messages(sessionId: String, page: Int, size: Int = PAGE_SIZE): Paged<ChatMessage> =
        apiCall { api.messages(sessionId, page, size) }
            .unwrap("история переписки")
            .toPaged(ChatMessage::from)

    /**
     * Свежая подпись вложения ровно той версии, что несёт сообщение. Листинг файлов для этого не
     * годится: он подписывает текущую версию, и старое сообщение открыло бы чужое содержимое.
     * Поэтому — история, страницу за страницей; глубже уже загруженного лентой искать незачем.
     *
     * `null` — вложения в просмотренных страницах нет.
     */
    suspend fun freshAttachmentUrl(sessionId: String, fileId: String, version: Int, pages: Int): String? {
        for (page in 0 until pages.coerceAtLeast(1)) {
            val chunk = apiCall { api.messages(sessionId, page, PAGE_SIZE) }.unwrap("история переписки")
            chunk.content.asSequence()
                .flatMap { it.parts.orEmpty() }
                .firstOrNull { it.fileId == fileId && it.version == version && !it.url.isNullOrBlank() }
                ?.let { return it.url }
            if (chunk.isLastPage) return null
        }
        return null
    }

    suspend fun startSession(agentId: String): ChatSession =
        ChatSession.from(
            apiCall { api.startSession(StartSessionRequest(agentId)) }.unwrap("новая переписка")
        )

    /**
     * Переименование. Ответ приходит обогащённым — бейджем и `isRunning`, — поэтому его кладут в
     * список как есть, не перезапрашивая страницу.
     */
    suspend fun renameSession(sessionId: String, title: String): ChatSession =
        ChatSession.from(
            apiCall { api.renameSession(sessionId, RenameSessionRequest(title)) }
                .unwrap("переименование переписки")
        )

    suspend fun closeSession(sessionId: String): ChatSession =
        ChatSession.from(
            apiCall { api.closeSession(sessionId) }.unwrap("закрытие переписки")
        )

    suspend fun send(
        sessionId: String,
        text: String?,
        fileIds: List<String>,
    ): SendMessageResponseDto = apiCall {
        api.sendMessage(
            sessionId,
            SendMessageRequest(
                text = text?.takeIf { it.isNotBlank() },
                parts = fileIds.takeIf { it.isNotEmpty() }?.map(::AttachmentRef),
            ),
        )
    }.unwrap("отправка сообщения")

    /**
     * @param lastReadRowId **id строки**, а не `messageId` — иначе 400. `null` помечает сессию
     *                      прочитанной до конца.
     */
    suspend fun markRead(sessionId: String, lastReadRowId: String?) {
        apiCall { api.markRead(sessionId, MarkReadRequest(lastReadRowId)) }
    }

    /** Останавливает переписку целиком, а не один запуск. Повторное нажатие безопасно. */
    suspend fun cancelSession(sessionId: String): CancelSessionDto =
        apiCall { api.cancelSession(sessionId) }.unwrap("остановка ответа")

    suspend fun userChannelToken(): CentrifugoTokenDto =
        apiCall { api.userToken() }.unwrap("токен личного канала")

    private fun <D, T> PageEnvelope<D>.toPaged(map: (D) -> T) = Paged(
        items = content.map(map),
        isLast = isLastPage,
        totalElements = totalElements,
    )

    companion object {
        /** Потолок на сервере — 100; просить больше бессмысленно, ответ молча урежут. */
        const val PAGE_SIZE = 50

        /** Единственный коннектор, с которым работает приложение. */
        const val CONNECTOR_WEBCHAT = "webchat"

        /** Предел заголовка на сервере: длиннее — `400`. Столько же режет автоматический. */
        const val TITLE_MAX_LENGTH = 80
    }
}
