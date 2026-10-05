package ru.agimate.mobile.feature.sessions

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
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
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.realtime.RealtimeClient
import ru.agimate.mobile.core.realtime.RealtimeEvent
import ru.agimate.mobile.core.realtime.upsertByActivity
import ru.agimate.mobile.data.drafts.Draft
import ru.agimate.mobile.data.drafts.DraftStore
import ru.agimate.mobile.data.webchat.ChatSession
import ru.agimate.mobile.data.webchat.WebchatRepository
import javax.inject.Inject

data class SessionsUiState(
    val agentName: String = "",
    val sessions: List<ChatSession> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: UiText? = null,
    val creating: Boolean = false,
    /** Незаконченные сообщения, по идентификатору переписки. */
    val drafts: Map<String, Draft> = emptyMap(),
    /** Переписка, которую переименовывают прямо сейчас; `null` — диалога нет. */
    val renaming: ChatSession? = null,
    val renameBusy: Boolean = false,
    /** Ошибка переименования живёт в диалоге, а не поверх списка: список-то цел. */
    val renameError: UiText? = null,
)

/**
 * Переписки одного агента. Закрытые приходят вместе с открытыми — фильтра на сервере нет,
 * различаем по `closedAt` и показываем приглушённо.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: WebchatRepository,
    private val realtime: RealtimeClient,
    drafts: DraftStore,
    savedState: SavedStateHandle,
) : ViewModel() {

    val agentId: String = checkNotNull(savedState["agentId"])

    private val _state = MutableStateFlow(
        SessionsUiState(agentName = savedState.get<String>("agentName").orEmpty())
    )
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var nextPage = 0

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    /** Первая страница хоть раз загрузилась: до этого живые строки не вставляем — см. [shouldInsert]. */
    private var loaded = false

    /**
     * Живые строки, пришедшие за время загрузки первой страницы. Ответ — снимок, снятый раньше них,
     * и без повторного наложения вернул бы старый заголовок или погасшее «печатает…».
     */
    private var liveDuringLoad = mutableListOf<RealtimeEvent.Session>()

    init {
        observeLiveRows()
        load()
        // Черновики локальные, и приходят они отдельно от серверного списка: строка знает про свой
        // по идентификатору переписки. Порядок строк при этом не меняется — он серверный, и между
        // страницами его не восстановить.
        viewModelScope.launch {
            drafts.drafts.collect { map -> _state.update { it.copy(drafts = map) } }
        }
    }

    /**
     * Строки переписок приходят целиком: заголовок, который платформа дала сама, закрытие с другого
     * устройства, счётчик, «печатает…». Канал общий на всё приложение, поэтому чужие строки
     * отбрасываем: другого агента, другого коннектора и субагентов.
     */
    private fun observeLiveRows() {
        viewModelScope.launch {
            realtime.events.collect { event ->
                when (event) {
                    is RealtimeEvent.Session -> {
                        if (!belongsHere(ChatSession.from(event.row))) return@collect
                        if (loadJob?.isActive == true) liveDuringLoad += event
                        _state.update { it.withLiveRow(event) }
                    }
                    RealtimeEvent.Resync -> load()
                    is RealtimeEvent.Contact, is RealtimeEvent.Message -> Unit
                }
            }
        }
    }

    private fun SessionsUiState.withLiveRow(event: RealtimeEvent.Session): SessionsUiState {
        val session = ChatSession.from(event.row)
        return copy(
            sessions = sessions.upsertByActivity(
                session,
                insertIfAbsent = shouldInsert(session, event.created, this),
                key = ChatSession::sessionId,
                activity = ChatSession::lastActivityAt,
            ),
            // Диалог переименования держит снимок строки — пусть он не отстаёт.
            renaming = renaming?.let { if (it.sessionId == session.sessionId) session else it },
        )
    }

    /**
     * Строки нет на экране — вставлять ли её.
     *
     * Список постраничный и отсортирован по свежести. Строка, свежее последней загруженной, по
     * серверному порядку уже на загруженных страницах — например, переписка с дальней страницы,
     * куда только что пришло сообщение, — и без вставки пропала бы совсем: её прежнее место
     * дочитанная страница не покажет. Строка старше последней лежит на непрочитанной странице и
     * приедет догрузкой.
     *
     * До первой удачной загрузки не вставляем ничего: одна строка спрятала бы экран ошибки и выдала
     * себя за весь список.
     */
    private fun shouldInsert(session: ChatSession, created: Boolean, state: SessionsUiState): Boolean {
        if (!loaded) return false
        if (created || state.endReached) return true
        val at = session.lastActivityAt ?: return false
        val oldest = state.sessions.lastOrNull()?.lastActivityAt ?: return true
        return at >= oldest
    }

    private fun belongsHere(session: ChatSession): Boolean =
        session.agentId == agentId &&
            session.connectorCode == WebchatRepository.CONNECTOR_WEBCHAT &&
            session.parentSessionId == null

    /**
     * Первая страница — при открытии, по повтору и после разрыва, пропущенное за который не
     * восстановилось. Скелетон только на пустом списке: показанный список перечитывается молча.
     */
    fun load() {
        // Догрузка, начатая до перечитывания, легла бы под свежую первую страницу с дырой между ними.
        loadMoreJob?.cancel()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            liveDuringLoad = mutableListOf()
            _state.update {
                it.copy(loading = it.sessions.isEmpty(), loadingMore = false, error = null)
            }
            try {
                val page = repository.sessions(agentId, 0)
                nextPage = 1
                loaded = true
                _state.update { current ->
                    liveDuringLoad.fold(
                        current.copy(sessions = page.items, loading = false, endReached = page.isLast)
                    ) { state, event -> state.withLiveRow(event) }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().text) }
            }
        }
    }

    /**
     * Следующая страница. Страницы считаются смещением от свежего края, а живые строки этот край
     * двигают: поднятая наверх переписка сдвигает окно, и её сосед приезжает второй раз. Повтор
     * отбрасываем — две строки с одним ключом роняют список.
     */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.endReached) return
        if (loadJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val page = repository.sessions(agentId, nextPage)
                nextPage++
                _state.update { state ->
                    val shown = state.sessions.mapTo(mutableSetOf()) { it.sessionId }
                    state.copy(
                        sessions = state.sessions + page.items.filter { it.sessionId !in shown },
                        loadingMore = false,
                        endReached = page.isLast,
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loadingMore = false, error = e.toApiException().text) }
            }
        }
    }

    fun startRename(session: ChatSession) {
        _state.update { it.copy(renaming = session, renameBusy = false, renameError = null) }
    }

    fun cancelRename() {
        _state.update { it.copy(renaming = null, renameBusy = false, renameError = null) }
    }

    /**
     * Переименование. Ответ приходит обогащённым, поэтому строку меняем на месте, а не
     * перезапрашиваем страницу: заново загруженный список потерял бы прокрутку и догруженные
     * страницы. Порядок строк при этом не съезжает — переименование не двигает `lastActivityAt`.
     */
    fun rename(title: String) {
        val target = _state.value.renaming ?: return
        val wanted = title.trim()
        if (wanted.isEmpty() || _state.value.renameBusy) return
        viewModelScope.launch {
            _state.update { it.copy(renameBusy = true, renameError = null) }
            try {
                val renamed = repository.renameSession(target.sessionId, wanted)
                _state.update { state ->
                    state.copy(
                        sessions = state.sessions.map {
                            if (it.sessionId == renamed.sessionId) renamed else it
                        },
                        renaming = null,
                        renameBusy = false,
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(renameBusy = false, renameError = e.toApiException().text)
                }
            }
        }
    }

    /** Новую переписку можно создавать в любой момент. */
    fun startNew(onCreated: (String) -> Unit) {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            try {
                val session = repository.startSession(agentId)
                _state.update { it.copy(creating = false) }
                onCreated(session.sessionId)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(creating = false, error = e.toApiException().text) }
            }
        }
    }
}
