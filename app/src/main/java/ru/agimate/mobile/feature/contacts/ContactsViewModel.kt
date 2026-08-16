package ru.agimate.mobile.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.realtime.OpenChatTracker
import ru.agimate.mobile.core.realtime.RealtimeClient
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.realtime.WebchatActivityPayload
import ru.agimate.mobile.data.webchat.Contact
import ru.agimate.mobile.data.webchat.MessagePreview
import ru.agimate.mobile.data.webchat.MessageStream
import ru.agimate.mobile.data.webchat.WebchatRepository
import javax.inject.Inject

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val realtime: RealtimeStatus = RealtimeStatus.Idle,
    /** Агент, для которого прямо сейчас заводится первая переписка. */
    val openingAgentId: String? = null,
) {
    val visible: List<Contact>
        get() = if (query.isBlank()) {
            contacts
        } else {
            val needle = query.trim().lowercase()
            contacts.filter {
                it.name.lowercase().contains(needle) ||
                    it.description?.lowercase()?.contains(needle) == true
            }
        }

    val isEmpty: Boolean get() = !loading && error == null && contacts.isEmpty()
}

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: WebchatRepository,
    private val realtime: RealtimeClient,
    private val openChats: OpenChatTracker,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        realtime.start()
        observeActivity()
        observeRealtimeStatus()
        load()
    }

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refresh && it.contacts.isEmpty(),
                    refreshing = refresh,
                    error = null,
                )
            }
            try {
                // Экран контактов — один запрос на страницу, но искать надо по всему списку, а
                // серверного поиска у этого эндпойнта нет. Агентов у человека единицы, поэтому
                // дочитываем страницы до конца: так и поиск честный, и порядок остаётся серверным.
                val collected = mutableListOf<Contact>()
                var page = 0
                while (page < MAX_PAGES) {
                    val chunk = repository.contacts(page)
                    collected += chunk.items
                    if (chunk.isLast) break
                    page++
                }
                _state.update {
                    it.copy(
                        contacts = collected,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = e.toApiException().message,
                    )
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    /**
     * Открыть переписку контакта. Если агенту ещё не писали, первую заводим здесь же: тап по
     * контакту в мессенджере значит «написать», а не «посмотреть список переписок».
     */
    fun openChat(contact: Contact, onReady: (String) -> Unit) {
        markContactRead(contact.agentId)

        val existing = contact.lastSessionId
        if (existing != null) {
            onReady(existing)
            return
        }
        if (_state.value.openingAgentId != null) return

        viewModelScope.launch {
            _state.update { it.copy(openingAgentId = contact.agentId) }
            try {
                val session = repository.startSession(contact.agentId)
                _state.update { it.copy(openingAgentId = null) }
                onReady(session.sessionId)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(openingAgentId = null, error = e.toApiException().message)
                }
            }
        }
    }

    /** Бейдж агента гасится при открытии его переписки. */
    fun markContactRead(agentId: String) {
        _state.update { current ->
            current.copy(
                contacts = current.contacts.map {
                    if (it.agentId == agentId) it.copy(unreadCount = 0) else it
                }
            )
        }
    }

    private fun observeActivity() {
        viewModelScope.launch {
            realtime.activity.collect { event -> applyActivity(event) }
        }
    }

    private fun observeRealtimeStatus() {
        viewModelScope.launch {
            realtime.status.collect { status -> _state.update { it.copy(realtime = status) } }
        }
    }

    /**
     * Событие тонкое: поднимает счётчик и обновляет превью, но список не пересортировывает — ключ
     * сортировки серверный, и восстановить его на клиенте между страницами нельзя.
     */
    private fun applyActivity(event: WebchatActivityPayload) {
        val agentId = event.agentId ?: return
        if (!MessageStream.parse(event.stream).countsAsUnread) return

        // Открытый прямо сейчас чат рисует сообщение сам — счётчик для него не растим.
        val countsToBadge = event.sessionId != null && event.sessionId != openChats.openSessionId

        _state.update { current ->
            current.copy(
                contacts = current.contacts.map { contact ->
                    if (contact.agentId != agentId) return@map contact
                    contact.copy(
                        unreadCount = if (countsToBadge) contact.unreadCount + 1 else contact.unreadCount,
                        lastSessionId = event.sessionId ?: contact.lastSessionId,
                        lastActivityAt = event.createdAt ?: contact.lastActivityAt,
                        preview = MessagePreview(
                            text = event.preview,
                            fromAgent = true,
                            hasAttachments = event.preview.isNullOrBlank(),
                            createdAt = event.createdAt,
                        ),
                        // Ответ пришёл — агент закончил работу.
                        isRunning = false,
                    )
                }
            )
        }
    }

    private companion object {
        /** Защита от бесконечного дочитывания, если сервер вдруг не отдаст признак последней. */
        const val MAX_PAGES = 20
    }
}
