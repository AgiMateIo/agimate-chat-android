package ru.agimate.mobile.feature.contacts

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
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
import ru.agimate.mobile.core.realtime.RealtimeClient
import ru.agimate.mobile.core.realtime.RealtimeEvent
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.realtime.upsertByActivity
import ru.agimate.mobile.data.drafts.Draft
import ru.agimate.mobile.data.drafts.DraftStore
import ru.agimate.mobile.data.webchat.Contact
import ru.agimate.mobile.data.webchat.WebchatRepository
import javax.inject.Inject

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: UiText? = null,
    val realtime: RealtimeStatus = RealtimeStatus.Idle,
    /** Агент, для которого прямо сейчас заводится первая переписка. */
    val openingAgentId: String? = null,
    /** Незаконченные сообщения, по агенту: у одного их может быть несколько, показываем свежий. */
    val drafts: Map<String, Draft> = emptyMap(),
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
    drafts: DraftStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        observeLiveRows()
        observeRealtimeStatus()
        load()
        observeDrafts(drafts)
    }

    /**
     * Черновики по агенту. У агента может быть несколько переписок и черновик в каждой — показываем
     * самый свежий, и тап ведёт именно в неё.
     */
    private fun observeDrafts(drafts: DraftStore) {
        viewModelScope.launch {
            drafts.drafts.collect { map ->
                val byAgent = map.values
                    .filter { it.agentId != null }
                    .groupBy { checkNotNull(it.agentId) }
                    .mapValues { (_, list) -> list.maxBy { it.updatedAt } }
                _state.update { it.copy(drafts = byAgent) }
            }
        }
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
                        error = e.toApiException().text,
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
        // Переписка с черновиком важнее последней: метка в строке обещает именно её, и открыться
        // должна она, иначе человек увидит пустое поле и решит, что набранное потерялось.
        val existing = _state.value.drafts[contact.agentId]?.sessionId ?: contact.lastSessionId
        if (existing != null) {
            onReady(existing)
            return
        }
        if (_state.value.openingAgentId != null) return

        viewModelScope.launch {
            _state.update { it.copy(openingAgentId = contact.agentId) }
            try {
                // Пустая переписка в контакт не попадает: lastSessionId сервер берёт из последнего
                // сообщения. Прошлый такой же тап уже мог завести переписку — сначала ищем её в
                // листинге (свежие сверху), иначе каждый тап по нетронутому агенту плодит пустые.
                val existing = repository.sessions(contact.agentId, page = 0).items
                    .firstOrNull { !it.isClosed }
                val session = existing ?: repository.startSession(contact.agentId)
                _state.update { it.copy(openingAgentId = null) }
                onReady(session.sessionId)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(openingAgentId = null, error = e.toApiException().text)
                }
            }
        }
    }

    /**
     * Строка контакта приходит целиком и заменяет прежнюю. Счётчик в ней — уже сумма по всем
     * перепискам агента: ни прибавлять к нему, ни гасить его здесь нельзя. Бейдж гаснет сам, когда
     * открытый чат отметит прочтение и сервер пришлёт строку заново.
     *
     * Список дочитан до конца, поэтому контакт, которого в нём нет, — новый агент, а не строка с
     * чужой страницы: его вставляем.
     */
    private fun observeLiveRows() {
        viewModelScope.launch {
            realtime.events.collect { event ->
                when (event) {
                    is RealtimeEvent.Contact -> _state.update { current ->
                        current.copy(
                            contacts = current.contacts.upsertByActivity(
                                Contact.from(event.row),
                                insertIfAbsent = true,
                                key = Contact::agentId,
                                activity = Contact::lastActivityAt,
                            )
                        )
                    }
                    RealtimeEvent.Resync -> load(refresh = false)
                    is RealtimeEvent.Session, is RealtimeEvent.Message -> Unit
                }
            }
        }
    }

    private fun observeRealtimeStatus() {
        viewModelScope.launch {
            realtime.status.collect { status -> _state.update { it.copy(realtime = status) } }
        }
    }

    private companion object {
        /** Защита от бесконечного дочитывания, если сервер вдруг не отдаст признак последней. */
        const val MAX_PAGES = 20
    }
}
