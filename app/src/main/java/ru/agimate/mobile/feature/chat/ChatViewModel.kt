package ru.agimate.mobile.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.network.OriginProvider
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.realtime.OpenChatTracker
import ru.agimate.mobile.core.realtime.RealtimeClient
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.realtime.SessionEvent
import ru.agimate.mobile.core.realtime.WebchatMessagePayload
import ru.agimate.mobile.data.webchat.Attachment
import ru.agimate.mobile.data.webchat.AttachmentUploader
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageDirection
import ru.agimate.mobile.data.webchat.MessageStream
import ru.agimate.mobile.data.webchat.PendingAttachment
import ru.agimate.mobile.data.webchat.WebchatRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val agentName: String = "",
    /** Выключенный агент: историю показываем, отправку запрещаем. */
    val agentEnabled: Boolean = true,
    /** Закрытая переписка: история есть, отправку сервер запрещает (400). */
    val closed: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    /** Агент сейчас работает. Гаснет приходом ответа или ошибки, а не опросом. */
    val isRunning: Boolean = false,
    /** Последний промежуточный шаг — показывается строкой, а не пузырём. */
    val liveProgress: String? = null,
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val sending: Boolean = false,
    val sendError: String? = null,
    val realtime: RealtimeStatus = RealtimeStatus.Idle,
) {
    val canSend: Boolean
        get() = agentEnabled && !closed && !sending &&
            (input.isNotBlank() || attachments.any { it.fileId != null }) &&
            attachments.none { it.uploading }

    val composerBlockedReason: String?
        get() = when {
            closed -> "Переписка закрыта — начните новую"
            !agentEnabled -> "Агент выключен"
            else -> null
        }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: WebchatRepository,
    private val realtime: RealtimeClient,
    private val uploader: AttachmentUploader,
    private val origins: OriginProvider,
    private val openChats: OpenChatTracker,
    savedState: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedState["sessionId"])
    private val agentId: String? = savedState["agentId"]

    private val _state = MutableStateFlow(
        ChatUiState(
            agentName = savedState.get<String>("agentName").orEmpty(),
            // Выключенный агент: историю показываем, отправку запрещаем. Признак приходит из
            // списка контактов — отдельного запроса за ним делать незачем.
            agentEnabled = savedState.get<String>("agentEnabled") != "0",
        )
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Лента хранится от новых к старым — как её отдаёт сервер и как рисует перевёрнутый список. */
    private var messages: List<ChatMessage> = emptyList()

    private var nextPage = 0
    private var loadOlderJob: Job? = null

    /** Чем в последний раз двигали указатель прочтения — чтобы не звать сервер на каждый скролл. */
    private var lastReadMarker: String? = null

    /** Половинки живой связи: соединение целиком и подписка на канал этой переписки. */
    private var connectionStatus = RealtimeStatus.Idle
    private var channelStatus = RealtimeStatus.Idle

    init {
        openChats.open(sessionId)
        observeRealtimeStatus()
        subscribeToSession()
        loadFirstPage()
        loadSessionState()
        // При открытии чата — отметка прочтения без тела: сессия прочитана до конца.
        markReadWholeSession()
    }

    fun fileUrl(relativeUrl: String): String = origins.fileUrl(relativeUrl)

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, sendError = null) }
    }

    fun dismissSendError() {
        _state.update { it.copy(sendError = null) }
    }

    // ---------------------------------------------------------------- история

    private fun loadFirstPage() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = repository.messages(sessionId, 0)
                // Не присваивание: подписка живёт с самого init, и пока грузилась история, в ленту
                // уже могли лечь живые сообщения. Второй раз их никто не пришлёт.
                messages = mergeHistoryPage(messages, page.items)
                nextPage = 1
                lastReadMarker = page.items.firstOrNull()?.rowId
                _state.update {
                    it.copy(
                        loading = false,
                        endReached = page.isLast,
                        items = buildChatItems(messages),
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().message) }
            }
        }
    }

    /** Листание вверх — это следующая страница: первая страница была концом переписки. */
    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || current.endReached || current.loading) return

        loadOlderJob?.cancel()
        loadOlderJob = viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            try {
                val page = repository.messages(sessionId, nextPage)
                messages = messages + page.items
                nextPage++
                _state.update {
                    it.copy(
                        loadingOlder = false,
                        endReached = page.isLast,
                        items = buildChatItems(messages),
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loadingOlder = false, error = e.toApiException().message) }
            }
        }
    }

    /**
     * Состояние самой переписки: закрыта ли и работает ли агент прямо сейчас. Отдельного эндпойнта
     * на одну сессию нет, поэтому берём из листинга переписок агента — он же и задуман для
     * восстановления состояния при открытии экрана.
     */
    private fun loadSessionState() {
        val agent = agentId ?: return
        viewModelScope.launch {
            runCatching { repository.sessions(agent, 0) }
                .onSuccess { page ->
                    val session = page.items.firstOrNull { it.sessionId == sessionId } ?: return@onSuccess
                    _state.update {
                        it.copy(closed = session.isClosed, isRunning = session.isRunning)
                    }
                }
        }
    }

    // ---------------------------------------------------------------- real-time

    private fun subscribeToSession() {
        viewModelScope.launch {
            realtime.sessionEvents(sessionId).collect { event ->
                when (event) {
                    is SessionEvent.Message -> applyLiveMessage(event.payload)
                    is SessionEvent.Status -> {
                        channelStatus = event.status
                        publishRealtimeStatus()
                    }
                }
            }
        }
    }

    private fun observeRealtimeStatus() {
        viewModelScope.launch {
            realtime.status.collect { status ->
                connectionStatus = status
                publishRealtimeStatus()
            }
        }
    }

    private fun publishRealtimeStatus() {
        val merged = RealtimeStatus.worseOf(connectionStatus, channelStatus)
        _state.update { it.copy(realtime = merged) }
    }

    private fun applyLiveMessage(payload: WebchatMessagePayload) {
        val incoming = ChatMessage(
            rowId = null,
            messageId = payload.messageId,
            direction = MessageDirection.parse(payload.direction),
            stream = MessageStream.parse(payload.stream),
            text = payload.text,
            attachments = payload.parts.orEmpty().map(Attachment::from),
            createdAt = payload.createdAt ?: Instant.now(),
        )

        val merge = mergeLiveMessage(messages, incoming)
        messages = merge.messages
        if (!merge.applied) return

        _state.update { current ->
            when (incoming.stream) {
                MessageStream.PROGRESS -> current.copy(isRunning = true, liveProgress = incoming.text)
                MessageStream.ANSWER, MessageStream.ERROR ->
                    current.copy(isRunning = false, liveProgress = null)

                MessageStream.NONE -> current
            }
        }
        publishItems()
    }

    private fun publishItems() {
        _state.update { it.copy(items = buildChatItems(messages)) }
    }

    // ---------------------------------------------------------------- отправка

    /**
     * Поле ввода не блокируется, пока агент отвечает: сообщение, отправленное во время ответа,
     * подхватывается тем же ответом — это штатный сценарий, а не гонка.
     */
    fun send() {
        val current = _state.value
        if (!current.canSend) return

        val text = current.input.trim().takeIf { it.isNotBlank() }
        val fileIds = current.attachments.mapNotNull { it.fileId }
        val localId = UUID.randomUUID().toString()

        val optimistic = ChatMessage(
            rowId = null,
            messageId = null,
            direction = MessageDirection.USER,
            stream = MessageStream.NONE,
            text = text,
            attachments = current.attachments.map {
                Attachment(fileId = it.fileId, mime = it.mime, size = it.sizeBytes, name = it.name, url = null)
            },
            createdAt = Instant.now(),
            pending = true,
            localId = localId,
        )

        messages = listOf(optimistic) + messages
        _state.update {
            it.copy(
                input = "",
                attachments = emptyList(),
                sending = true,
                sendError = null,
                items = buildChatItems(messages),
            )
        }

        viewModelScope.launch {
            try {
                val response = repository.send(sessionId, text, fileIds)
                updateLocal(localId) { it.copy(pending = false, messageId = response.messageId) }
                _state.update { it.copy(sending = false, isRunning = true) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val error = e.toApiException()
                updateLocal(localId) { it.copy(pending = false, failed = true) }
                _state.update { it.copy(sending = false, sendError = error.message) }
            }
        }
    }

    /** Повтор неудачной отправки: убираем неудачное сообщение и кладём текст обратно в поле. */
    fun retry(message: ChatMessage) {
        val localId = message.localId ?: return
        messages = messages.filterNot { it.localId == localId }
        _state.update {
            it.copy(
                input = message.text.orEmpty(),
                sendError = null,
                items = buildChatItems(messages),
            )
        }
    }

    private fun updateLocal(localId: String, transform: (ChatMessage) -> ChatMessage) {
        val index = messages.indexOfFirst { it.localId == localId }
        if (index < 0) return
        messages = messages.toMutableList().apply { this[index] = transform(this[index]) }
        publishItems()
    }

    // ---------------------------------------------------------------- вложения

    fun addAttachments(uris: List<Uri>) {
        val current = _state.value.attachments
        val room = AttachmentUploader.MAX_ATTACHMENTS - current.size
        if (room <= 0) {
            _state.update { it.copy(sendError = "Больше пяти вложений в одном сообщении нельзя") }
            return
        }

        val accepted = uris.take(room).map { uri ->
            uploader.describe(uri, UUID.randomUUID().toString())
        }
        _state.update { it.copy(attachments = it.attachments + accepted) }

        accepted.forEach { attachment ->
            viewModelScope.launch {
                uploader.upload(attachment)
                    .onSuccess { fileId ->
                        updateAttachment(attachment.localId) {
                            it.copy(fileId = fileId, uploading = false)
                        }
                    }
                    .onFailure { error ->
                        updateAttachment(attachment.localId) {
                            it.copy(uploading = false, error = error.toApiException().message)
                        }
                    }
            }
        }
    }

    fun removeAttachment(localId: String) {
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.localId == localId }) }
    }

    private fun updateAttachment(localId: String, transform: (PendingAttachment) -> PendingAttachment) {
        _state.update { current ->
            current.copy(
                attachments = current.attachments.map {
                    if (it.localId == localId) transform(it) else it
                }
            )
        }
    }

    // ---------------------------------------------------------------- прочее

    /**
     * Остановка. Отменяем **сессию**, а не запуск: отмена одного запуска позволит стартовать
     * следующему из очереди этой же переписки. Идемпотентно, повторное нажатие безопасно.
     */
    fun stop() {
        viewModelScope.launch {
            runCatching { repository.cancelSession(sessionId) }
                .onSuccess { _state.update { it.copy(isRunning = false, liveProgress = null) } }
                .onFailure { error -> _state.update { it.copy(sendError = error.toApiException().message) } }
        }
    }

    /** Новую переписку можно создавать в любой момент. */
    fun startNewSession(onCreated: (String) -> Unit) {
        val agent = agentId ?: return
        viewModelScope.launch {
            runCatching { repository.startSession(agent) }
                .onSuccess { onCreated(it.sessionId) }
                .onFailure { error -> _state.update { it.copy(sendError = error.toApiException().message) } }
        }
    }

    /** Закрытие гасит бейдж: сессия помечается прочитанной целиком. */
    fun closeSession(onClosed: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.closeSession(sessionId) }
                .onSuccess {
                    _state.update { it.copy(closed = true) }
                    onClosed()
                }
                .onFailure { error -> _state.update { it.copy(sendError = error.toApiException().message) } }
        }
    }

    /**
     * Человек долистал до конца — двигаем указатель прочтения. Указатель ходит только вперёд,
     * повторный вызов со старым значением не ошибка.
     *
     * У сообщения, пришедшего по WebSocket, id строки нет — оно родилось не из листинга. Для такого
     * шлём отметку без тела: это и значит «прочитано до конца».
     */
    fun onReachedBottom() {
        val newest = messages.firstOrNull() ?: return
        val marker = newest.rowId ?: newest.messageId ?: return
        if (marker == lastReadMarker) return
        lastReadMarker = marker
        // Передаётся id строки, а не messageId — иначе 400.
        viewModelScope.launch { runCatching { repository.markRead(sessionId, newest.rowId) } }
    }

    private fun markReadWholeSession() {
        viewModelScope.launch { runCatching { repository.markRead(sessionId, null) } }
    }

    override fun onCleared() {
        openChats.close(sessionId)
        super.onCleared()
    }
}
