package ru.agimate.mobile.feature.sessions

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.network.toApiException
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
    drafts: DraftStore,
    savedState: SavedStateHandle,
) : ViewModel() {

    val agentId: String = checkNotNull(savedState["agentId"])

    private val _state = MutableStateFlow(
        SessionsUiState(agentName = savedState.get<String>("agentName").orEmpty())
    )
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var nextPage = 0

    init {
        load()
        // Черновики локальные, и приходят они отдельно от серверного списка: строка знает про свой
        // по идентификатору переписки. Порядок строк при этом не меняется — он серверный, и между
        // страницами его не восстановить.
        viewModelScope.launch {
            drafts.drafts.collect { map -> _state.update { it.copy(drafts = map) } }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = repository.sessions(agentId, 0)
                nextPage = 1
                _state.update {
                    it.copy(sessions = page.items, loading = false, endReached = page.isLast)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().text) }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.endReached) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val page = repository.sessions(agentId, nextPage)
                nextPage++
                _state.update {
                    it.copy(
                        sessions = it.sessions + page.items,
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
