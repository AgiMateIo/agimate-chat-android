package ru.agimate.mobile.feature.chat

import android.content.Intent
import androidx.annotation.StringRes
import ru.agimate.mobile.R
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.OriginProvider
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.realtime.OpenChatTracker
import ru.agimate.mobile.core.realtime.RealtimeClient
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.realtime.SessionEvent
import ru.agimate.mobile.core.realtime.WebchatMessagePayload
import ru.agimate.mobile.core.share.FileNotice
import ru.agimate.mobile.core.share.FileStore
import ru.agimate.mobile.core.share.RemoteFile
import ru.agimate.mobile.core.share.SavedTo
import ru.agimate.mobile.core.share.Sharing
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.drafts.DraftStore
import ru.agimate.mobile.data.files.FilesRepository
import ru.agimate.mobile.data.files.StoredFile
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

/**
 * Разовое действие, которое экрану нужно совершить за ViewModel.
 *
 * Запуск чужого приложения — не состояние: показать диалог выбора второй раз после поворота экрана
 * было бы не восстановлением, а сюрпризом.
 */
sealed interface ChatEffect {
    /** Готовый интент: собрать его — дело ViewModel, запустить — экрана, у него есть контекст. */
    data class Launch(val intent: Intent) : ChatEffect
}

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
    val error: UiText? = null,
    /** Агент сейчас работает. Гаснет приходом ответа или ошибки, а не опросом. */
    val isRunning: Boolean = false,
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val sending: Boolean = false,
    val sendError: UiText? = null,
    /** Что сейчас происходит с файлом или буфером обмена. */
    val fileNotice: FileNotice? = null,
    val realtime: RealtimeStatus = RealtimeStatus.Idle,
) {
    val canSend: Boolean
        get() = agentEnabled && !closed && !sending &&
            (input.isNotBlank() || attachments.any { it.fileId != null }) &&
            attachments.none { it.uploading }

    val composerBlockedReason: UiText?
        get() = when {
            closed -> uiText(R.string.chat_composer_closed)
            !agentEnabled -> uiText(R.string.chat_composer_agent_disabled)
            else -> null
        }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: WebchatRepository,
    private val realtime: RealtimeClient,
    private val uploader: AttachmentUploader,
    private val drafts: DraftStore,
    private val storedFiles: FilesRepository,
    private val files: FileStore,
    private val sharing: Sharing,
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

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    /** Лента хранится от новых к старым — как её отдаёт сервер и как рисует перевёрнутый список. */
    private var messages: List<ChatMessage> = emptyList()

    private var nextPage = 0
    private var loadOlderJob: Job? = null

    /** Дело с файлом — одно за раз; строка о нём — тоже одна. */
    private var fileJob: Job? = null

    /** Вложения, за свежей подписью которых уже ходили ради картинки. */
    private val refreshedImages = mutableSetOf<String>()
    private var noticeJob: Job? = null

    /** Чем в последний раз двигали указатель прочтения — чтобы не звать сервер на каждый скролл. */
    private var lastReadMarker: String? = null

    /** Половинки живой связи: соединение целиком и подписка на канал этой переписки. */
    private var connectionStatus = RealtimeStatus.Idle
    private var channelStatus = RealtimeStatus.Idle
    private var slowConnectJob: Job? = null

    init {
        openChats.open(sessionId)
        restoreDraft()
        observeDraftAttachments()
        observeRealtimeStatus()
        subscribeToSession()
        loadFirstPage()
        loadSessionState()
        // При открытии чата — отметка прочтения без тела: сессия прочитана до конца.
        markReadWholeSession()
    }

    // ---------------------------------------------------------------- черновик

    /**
     * Набранное, но не отправленное. Хранится дольше экрана, поэтому переход в соседнюю переписку
     * его больше не теряет.
     */
    private fun restoreDraft() {
        val draft = drafts.draft(sessionId) ?: return
        _state.update { it.copy(input = draft.text, attachments = draft.attachments) }
        if (draft.attachments.isNotEmpty()) validateDraftAttachments(draft.attachments)
    }

    /**
     * Вложения приходят из хранилища, а не хранятся здесь: загрузка идёт в области приложения и
     * доходит даже тогда, когда экрана уже нет. Второй источник правды дал бы состояние, зависящее
     * от того, успел ли человек уйти.
     *
     * Текст, наоборот, живёт в состоянии экрана и пишется в хранилище на ходу: снаружи его никто не
     * меняет, а круг через поток на каждой букве поле ввода не обрадует.
     */
    private fun observeDraftAttachments() {
        viewModelScope.launch {
            drafts.drafts
                .map { it[sessionId]?.attachments.orEmpty() }
                .distinctUntilChanged()
                .collect { attachments ->
                    _state.update { it.copy(attachments = attachments) }
                }
        }
    }

    /**
     * Файл из черновика мог уйти по сроку: у произведённого коннектором он семь дней, и удалить его
     * можно руками. Сервер проверяет каждое вложение и отказывает **всему** сообщению — то есть
     * недельный черновик с одной мёртвой картинкой не отправился бы вовсе, и виноват был бы вроде
     * бы человек.
     *
     * Поэтому проверяем на входе: живые остаются, ушедшие убираются, и об этом говорится вслух.
     */
    private fun validateDraftAttachments(attachments: List<PendingAttachment>) {
        viewModelScope.launch {
            val alive = attachments.filter { attachment ->
                val fileId = attachment.fileId ?: return@filter true
                runCatching { storedFiles.freshUrl(fileId, name = attachment.name) }
                    .getOrElse { return@launch } != null
            }
            if (alive.size == attachments.size) return@launch
            drafts.replaceAttachments(sessionId, alive)
            notice(uiText(R.string.draft_attachments_gone), failed = true)
        }
    }

    /** Записать черновик немедленно — по остановке экрана. */
    fun persistDraft() = drafts.flush()

    fun fileUrl(url: String): String = origins.fileUrl(url)

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, sendError = null) }
        drafts.setText(sessionId, agentId, value)
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
                _state.update { it.copy(loading = false, error = e.toApiException().text) }
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
                _state.update { it.copy(loadingOlder = false, error = e.toApiException().text) }
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

        slowConnectJob?.cancel()
        if (merged == RealtimeStatus.Connected) return
        // Centrifugo повторяет подключение молча и бесконечно, о новых попытках не сообщая. Значит,
        // «подключаюсь» само по себе никогда не станет ошибкой, и неверный адрес WebSocket выглядит
        // как исправный чат, в который просто ничего не приходит. Считаем затянувшееся подключение
        // потерей связи — человеку важно не состояние сокета, а то, что лента больше не живая.
        slowConnectJob = viewModelScope.launch {
            delay(SLOW_CONNECT_MS)
            _state.update { it.copy(realtime = RealtimeStatus.Disconnected) }
        }
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

        // Лента и признак работы обновляются одним разом. Двумя обновлениями заготовка ответа
        // гасла бы в одном кадре, а сам ответ встал бы в следующем — между ними лента осталась бы
        // без обоих и подпрыгнула.
        _state.update { current ->
            val next = current.copy(items = buildChatItems(messages))
            when (incoming.stream) {
                MessageStream.PROGRESS -> next.copy(isRunning = true)
                MessageStream.ANSWER, MessageStream.ERROR -> next.copy(isRunning = false)
                MessageStream.NONE -> next
            }
        }
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
        // Сообщение ушло в ленту — черновика больше нет. Неудачная отправка его не воскрешает:
        // сообщение остаётся в ленте с повтором, и второе место для того же текста только запутает.
        drafts.clear(sessionId)
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
                _state.update { it.copy(sending = false, sendError = error.text) }
            }
        }
    }

    /** Повтор неудачной отправки: убираем неудачное сообщение и кладём текст обратно в поле. */
    fun retry(message: ChatMessage) {
        val localId = message.localId ?: return
        messages = messages.filterNot { it.localId == localId }
        drafts.setText(sessionId, agentId, message.text.orEmpty())
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
            _state.update { it.copy(sendError = uiText(R.string.chat_attachments_limit)) }
            return
        }

        val accepted = uris.take(room).map { uri ->
            uploader.describe(uri, UUID.randomUUID().toString())
        }
        // Загрузку ведёт хранилище черновиков: в его области она переживает уход с экрана.
        drafts.attach(sessionId, agentId, accepted)
    }

    /**
     * Прикрепить файл, который уже лежит на сервере: `fileId` у него есть, загружать нечего.
     *
     * Лимит общий с локальными вложениями — считает их сервер, а не экран. Повтор молча
     * игнорируется: один и тот же файл двумя частями одного сообщения смысла не имеет.
     */
    fun attachStored(file: StoredFile) {
        val current = _state.value.attachments
        if (current.any { it.fileId == file.id }) return
        if (current.size >= AttachmentUploader.MAX_ATTACHMENTS) {
            _state.update { it.copy(sendError = uiText(R.string.chat_attachments_limit)) }
            return
        }
        drafts.attach(
            sessionId = sessionId,
            agentId = agentId,
            items = listOf(
                PendingAttachment(
                    localId = UUID.randomUUID().toString(),
                    name = file.name.orEmpty(),
                    mime = file.mime,
                    sizeBytes = file.size,
                    uri = null,
                    fileId = file.id,
                    uploading = false,
                )
            ),
        )
    }

    fun removeAttachment(localId: String) {
        drafts.removeAttachment(sessionId, localId)
    }

    // ------------------------------------------------- поделиться и сохранить

    /** Текст сообщения — наружу. Вложения у сообщения свои действия, они не про текст. */
    fun shareMessage(message: ChatMessage) {
        val text = message.text?.takeIf { it.isNotBlank() } ?: return
        handOff(sharing.shareText(text))
    }

    fun copyMessage(message: ChatMessage) {
        val text = message.text?.takeIf { it.isNotBlank() } ?: return
        sharing.copy(text)
        if (!sharing.clipboardConfirmsItself) notice(uiText(R.string.file_copied))
    }

    fun openAttachment(attachment: Attachment) = withFile(attachment, R.string.file_opening) { file ->
        handOff(sharing.openFile(files.cache(file), file.mime))
        null
    }

    fun shareAttachment(attachment: Attachment) = withFile(attachment, R.string.file_preparing) { file ->
        handOff(sharing.shareFile(files.cache(file), file.mime))
        null
    }

    fun saveAttachment(attachment: Attachment) = withFile(attachment, R.string.file_saving) { file ->
        uiText(
            when (files.save(file)) {
                SavedTo.GALLERY -> R.string.file_saved_gallery
                SavedTo.DOWNLOADS -> R.string.file_saved_downloads
            }
        )
    }

    /** Разрешения на память не дали. Промолчать нельзя: нажатие осталось бы без всякого ответа. */
    fun onSaveDenied() = notice(uiText(R.string.file_save_denied), failed = true)

    /** Снимок не состоялся: файл негде завести или камеры не нашлось. */
    fun onPhotoFailed() = notice(uiText(R.string.error_photo_failed), failed = true)

    /**
     * Общая обвязка действий с файлом: скачать его нужно всем троим, и все трое делают это не
     * мгновенно.
     *
     * [block] возвращает строку, которой заканчивается дело, или `null`, если дальше говорит уже
     * не приложение: диалог выбора и так виден, и подпись под ним была бы лишней.
     */
    private fun withFile(
        attachment: Attachment,
        @StringRes progress: Int,
        block: suspend (RemoteFile) -> UiText?,
    ) {
        if (attachment.fileId == null) {
            // Своё сообщение, пока сервер не подтвердил отправку: файла на той стороне ещё нет.
            notice(uiText(R.string.file_not_uploaded_yet), failed = true)
            return
        }
        // Второй тап не запускает вторую закачку: 50 МБ по мобильной сети качаются небыстро.
        if (fileJob?.isActive == true) return

        fileJob = viewModelScope.launch {
            working(progress)
            try {
                val done = withFreshLink(attachment, block)
                if (done == null) clearNotice() else notice(done)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                notice(e.toApiException().text, failed = true)
            }
        }
    }

    /**
     * Одна повторная попытка со свежей подписью.
     *
     * `403` на скачивании значит, что переписка открыта давно: подпись живёт пятнадцать минут. Файл
     * при этом на месте, и говорить человеку про доступ — врать. Ссылки нет вовсе у только что
     * отправленного своего вложения — там она и не приходила, и путь ровно тот же.
     *
     * Не нашлось в листинге переписки — вот тогда файл действительно ушёл по сроку.
     */
    private suspend fun withFreshLink(
        attachment: Attachment,
        block: suspend (RemoteFile) -> UiText?,
    ): UiText? {
        val fileId = checkNotNull(attachment.fileId)
        val address = attachment.url?.takeIf { it.isNotBlank() }?.let(origins::fileUrl)
        if (address != null) {
            try {
                return block(attachment.remote(address))
            } catch (_: ApiException.Forbidden) {
                // Ниже — вторая и последняя попытка.
            }
        }

        val fresh = freshUrl(attachment) ?: throw ApiException.NotFound(
            uiText(R.string.error_file_gone),
            "file $fileId gone from listing",
        )
        // Вложение запоминает новый адрес: следующему действию ходить за ним второй раз незачем.
        patchUrl(fileId, fresh)
        return block(attachment.remote(origins.fileUrl(fresh)))
    }

    /**
     * Картинка не нарисовалась. Почти всегда это протухшая подпись, а не потерянный доступ, — идём
     * за свежей, и пузырь перерисуется сам по новому адресу.
     *
     * Загрузчик картинок не сообщает код отказа, поэтому от бесконечного круга спасает только
     * память о том, за какими файлами уже ходили: оборванная сеть иначе гоняла бы перезапрос
     * столько раз, сколько лента успеет перерисоваться.
     */
    fun onImageFailed(attachment: Attachment) {
        val fileId = attachment.fileId ?: return
        if (!refreshedImages.add(fileId)) return
        viewModelScope.launch {
            val fresh = runCatching { freshUrl(attachment) }.getOrNull() ?: return@launch
            patchUrl(fileId, fresh)
        }
    }

    /** Файлы переписки — та же выдача, что открывает вложения экраном файлов, только фильтром. */
    private suspend fun freshUrl(attachment: Attachment): String? =
        storedFiles.freshUrl(
            fileId = attachment.fileId ?: return null,
            sessionId = sessionId,
            name = attachment.name,
        )

    private fun patchUrl(fileId: String, url: String) {
        messages = messages.map { message ->
            if (message.attachments.none { it.fileId == fileId }) return@map message
            message.copy(
                attachments = message.attachments.map {
                    if (it.fileId == fileId) it.copy(url = url) else it
                }
            )
        }
        publishItems()
    }

    private fun Attachment.remote(address: String) =
        RemoteFile(id = fileId, url = address, name = name, mime = mime)

    private fun handOff(intent: Intent) {
        _effects.trySend(ChatEffect.Launch(intent))
    }

    /** Строка о том, что уже случилось: гаснет сама — держать её на экране незачем. */
    private fun notice(text: UiText, failed: Boolean = false) {
        noticeJob?.cancel()
        _state.update { it.copy(fileNotice = FileNotice(text, failed)) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MILLIS)
            _state.update { it.copy(fileNotice = null) }
        }
    }

    /** Строка о том, что происходит прямо сейчас: она и есть признак работы, гаснуть ей нельзя. */
    private fun working(@StringRes text: Int) {
        noticeJob?.cancel()
        _state.update { it.copy(fileNotice = FileNotice(uiText(text))) }
    }

    private fun clearNotice() {
        noticeJob?.cancel()
        _state.update { it.copy(fileNotice = null) }
    }

    // ---------------------------------------------------------------- прочее

    /**
     * Остановка. Отменяем **сессию**, а не запуск: отмена одного запуска позволит стартовать
     * следующему из очереди этой же переписки. Идемпотентно, повторное нажатие безопасно.
     */
    fun stop() {
        viewModelScope.launch {
            runCatching { repository.cancelSession(sessionId) }
                .onSuccess { _state.update { it.copy(isRunning = false) } }
                .onFailure { error -> _state.update { it.copy(sendError = error.toApiException().text) } }
        }
    }

    /** Новую переписку можно создавать в любой момент. */
    fun startNewSession(onCreated: (String) -> Unit) {
        val agent = agentId ?: return
        viewModelScope.launch {
            runCatching { repository.startSession(agent) }
                .onSuccess { onCreated(it.sessionId) }
                .onFailure { error -> _state.update { it.copy(sendError = error.toApiException().text) } }
        }
    }

    /** Закрытие гасит бейдж: сессия помечается прочитанной целиком. */
    fun closeSession(onClosed: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.closeSession(sessionId) }
                .onSuccess {
                    // В закрытую переписку писать нельзя — черновику там висеть незачем.
                    drafts.clear(sessionId)
                    _state.update { it.copy(closed = true) }
                    onClosed()
                }
                .onFailure { error -> _state.update { it.copy(sendError = error.toApiException().text) } }
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

    private companion object {
        /** Сколько ждать живую связь, прежде чем признать её потерянной. */
        const val SLOW_CONNECT_MS = 10_000L

        /** Сколько висит строка о законченном деле с файлом. */
        const val NOTICE_MILLIS = 4_000L
    }
}
