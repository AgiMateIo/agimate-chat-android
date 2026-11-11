package ru.agimate.mobile.feature.views

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
import kotlinx.serialization.json.JsonObject
import ru.agimate.mobile.R
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.realtime.RealtimeEvent
import ru.agimate.mobile.core.realtime.RealtimeEventsSource
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.views.AgentViewContent
import ru.agimate.mobile.data.views.SessionToolCall
import ru.agimate.mobile.data.views.ToolCallRefresh
import ru.agimate.mobile.data.views.ToolCallView
import ru.agimate.mobile.data.views.ToolCallViewSource
import ru.agimate.mobile.data.views.ViewsRepository
import javax.inject.Inject

data class AgentViewUiState(
    val loading: Boolean = true,
    val content: AgentViewContent? = null,
    val call: SessionToolCall? = null,
    val error: UiText? = null,
    /** Вью больше нет: экран закрывается назад, чат остаётся. */
    val close: Boolean = false,
    /** Ключ хоста: растёт при повторе, чтобы WebView пересоздался. */
    val revision: Int = 0,
)

/**
 * Полный экран одной вью.
 *
 * В маршруте только идентификаторы, поэтому загрузчик идёт последовательно: сначала вызов (он
 * знает `connectionId` и `viewUri`), потом страница. Хост тот же, что у карточки; `size-changed`
 * здесь игнорируется — страница и так занимает весь экран.
 */
@HiltViewModel
class AgentViewViewModel @Inject constructor(
    private val source: ToolCallViewSource,
    private val views: ViewsRepository,
    private val realtime: RealtimeEventsSource,
    savedState: SavedStateHandle,
) : ViewModel() {

    val agentId: String = checkNotNull(savedState["agentId"])
    val sessionId: String = checkNotNull(savedState["sessionId"])
    val toolCallId: String = checkNotNull(savedState["toolCallId"])

    private val _state = MutableStateFlow(AgentViewUiState())
    val state: StateFlow<AgentViewUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        load()
        observeSignals()
    }

    fun retry() = load()

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val view = source.load(agentId, sessionId, toolCallId)) {
                    is ToolCallView.Ready -> _state.update {
                        it.copy(
                            loading = false,
                            content = view.content,
                            call = view.call,
                            revision = it.revision + 1,
                        )
                    }

                    ToolCallView.NoView, ToolCallView.PageGone ->
                        _state.update { it.copy(loading = false, close = true) }

                    is ToolCallView.CallFailed ->
                        _state.update { it.copy(loading = false, error = view.message) }

                    is ToolCallView.PageFailed ->
                        _state.update { it.copy(loading = false, error = view.message) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(loading = false, error = error.toApiException().text)
                }
            }
        }
    }

    /**
     * Сигнал `session.tool_call` по этой сессии и этому вызову — повод догрузить результат.
     *
     * `tool-input` уже ушёл; хост дошлёт только `tool-result`, не пересоздавая WebView.
     */
    private fun observeSignals() {
        viewModelScope.launch {
            realtime.events.collect { event ->
                if (event is RealtimeEvent.ToolCall &&
                    event.payload.sessionId == sessionId &&
                    event.payload.toolCallIds?.contains(toolCallId) == true
                ) {
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        // Пока страница не смонтирована, начальная загрузка сама принесёт актуальный результат.
        if (_state.value.content == null || _state.value.call == null) return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            when (val refresh = source.refreshCall(sessionId, toolCallId)) {
                is ToolCallRefresh.Updated -> {
                    val call = refresh.call
                    if (call.viewUri == null || call.connectionId == null) {
                        _state.update { it.copy(close = true) }
                    } else {
                        _state.update { it.copy(call = call) }
                    }
                }

                ToolCallRefresh.Gone -> _state.update { it.copy(close = true) }
                ToolCallRefresh.Failed -> Unit
            }
        }
    }

    /** Рендерер упал: ошибка и повтор, приложение живо. */
    fun onRendererGone() {
        _state.update {
            it.copy(loading = false, error = uiText(R.string.views_crashed))
        }
    }

    /** Вызов тула из страницы: `agentId` и `connectionId` — из нативного состояния экрана. */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        val call = _state.value.call ?: error("tool call is not loaded")
        val connectionId = call.connectionId ?: error("tool call has no connection")
        return views.callTool(agentId, connectionId, name, arguments)
    }
}
