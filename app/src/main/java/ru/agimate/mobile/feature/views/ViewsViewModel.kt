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
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.views.AgentView
import ru.agimate.mobile.data.views.ViewsRepository
import javax.inject.Inject

/** Открытая страница вью. [revision] — ключ, по которому хост пересоздаёт WebView при перезагрузке. */
data class OpenedView(
    val view: AgentView,
    val loading: Boolean = true,
    val content: String? = null,
    val error: UiText? = null,
    val revision: Int = 0,
)

data class ViewsUiState(
    val loading: Boolean = true,
    val views: List<AgentView> = emptyList(),
    val error: UiText? = null,
    val opened: OpenedView? = null,
)

/**
 * Вью агента: список привязок и одна открытая страница.
 *
 * Страница приходит HTML-ом по HTTP и живёт в песочнице хоста, поэтому ViewModel возит только
 * состояние — содержимое, ошибку и ревизию, — а не сам документ.
 */
@HiltViewModel
class ViewsViewModel @Inject constructor(
    private val repository: ViewsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    val agentId: String = checkNotNull(savedState["agentId"])
    val agentName: String = savedState.get<String>("agentName").orEmpty()

    private val _state = MutableStateFlow(ViewsUiState())
    val state: StateFlow<ViewsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var contentJob: Job? = null

    init {
        load()
    }

    /** Список перечитывается целиком: кеша нет, вью появляются и исчезают вместе с привязками. */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val views = repository.list(agentId)
                _state.update { it.copy(loading = false, views = views) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().text) }
            }
        }
    }

    /** Открыть страницу. Содержимое грузится заново, ревизия начинается с нуля. */
    fun open(view: AgentView) {
        _state.update { it.copy(opened = OpenedView(view = view)) }
        fetch(view, revision = 0)
    }

    /**
     * Перезагрузить открытую страницу.
     *
     * Ревизия растёт, даже когда сервер вернул тот же самый HTML: хост пересоздаётся по ней, и без
     * прироста «Обновить» не сдвинул бы страницу с места. Растёт она только при успехе — у ошибки
     * остаётся прежняя ревизия, а её текст исчезает сразу, как только запрос пошёл.
     */
    fun reload() {
        val opened = _state.value.opened ?: return
        fetch(opened.view, revision = opened.revision + 1)
    }

    /** Закрыть страницу. Незавершённый запрос содержимого отменяется: его ответу уже некуда лечь. */
    fun close() {
        contentJob?.cancel()
        contentJob = null
        _state.update { it.copy(opened = null) }
    }

    /**
     * Рендерер страницы упал — например, системе не хватило памяти. Содержимое после этого
     * недействительно, но приложение живо: показываем ошибку, а «Обновить» пересоздаст хост.
     */
    fun onRendererGone() {
        _state.update { state ->
            val opened = state.opened ?: return@update state
            state.copy(opened = opened.copy(loading = false, error = uiText(R.string.views_crashed)))
        }
    }

    /**
     * Вызов тула из открытой страницы. Привязка берётся из нативного состояния вью: страница
     * присылает только имя тула и аргументы, поэтому `connectionId` и `agentId` в них — такие же
     * аргументы, как любые другие, а не адрес запроса.
     */
    suspend fun callTool(view: AgentView, name: String, arguments: JsonObject): JsonObject =
        repository.callTool(agentId, view.connectionId, name, arguments)

    /**
     * Запрос содержимого. Предыдущий отменяется: открыли другую вью или нажали «Обновить» дважды —
     * применяется только последний ответ.
     */
    private fun fetch(view: AgentView, revision: Int) {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            _state.update { state ->
                val opened = state.opened
                if (opened == null || opened.view != view) state
                else state.copy(opened = opened.copy(loading = true, error = null))
            }
            try {
                val content = repository.content(agentId, view.connectionId, view.uri)
                _state.update { state ->
                    // Вью могли закрыть, пока страница ехала: ответу уже некуда лечь.
                    val opened = state.opened
                    if (opened == null || opened.view != view) state
                    else state.copy(
                        opened = opened.copy(
                            loading = false,
                            content = content.html,
                            revision = revision,
                        ),
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { state ->
                    val opened = state.opened
                    if (opened == null || opened.view != view) state
                    else state.copy(
                        opened = opened.copy(
                            loading = false,
                            error = e.toApiException().text,
                        ),
                    )
                }
            }
        }
    }
}
