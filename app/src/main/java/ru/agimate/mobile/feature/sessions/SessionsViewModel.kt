package ru.agimate.mobile.feature.sessions

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
import ru.agimate.mobile.data.webchat.ChatSession
import ru.agimate.mobile.data.webchat.WebchatRepository
import javax.inject.Inject

data class SessionsUiState(
    val agentName: String = "",
    val sessions: List<ChatSession> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val creating: Boolean = false,
)

/**
 * Переписки одного агента. Закрытые приходят вместе с открытыми — фильтра на сервере нет,
 * различаем по `closedAt` и показываем приглушённо.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: WebchatRepository,
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
                _state.update { it.copy(loading = false, error = e.toApiException().message) }
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
                _state.update { it.copy(loadingMore = false, error = e.toApiException().message) }
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
                _state.update { it.copy(creating = false, error = e.toApiException().message) }
            }
        }
    }
}
