package ru.agimate.mobile.feature.createagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.data.agents.AgentPresetDto
import ru.agimate.mobile.data.agents.AgentsApi
import ru.agimate.mobile.data.agents.CreateAgentRequest
import ru.agimate.mobile.data.webchat.WebchatRepository
import javax.inject.Inject

data class CreateAgentUiState(
    val presets: List<AgentPresetDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Выбранная роль — второй шаг мастера. */
    val selected: AgentPresetDto? = null,
    val name: String = "",
    val instructions: String = "",
    val instructionsExpanded: Boolean = false,
    val creating: Boolean = false,
    val createError: String? = null,
)

/** Созданный агент и открытая для него переписка. */
data class CreatedAgent(val agentId: String, val agentName: String, val sessionId: String)

/**
 * Создание агента ровно в два шага: галерея ролей и подтверждение. Ни интеграций, ни навыков, ни
 * выбора моделей на этом пути нет.
 */
@HiltViewModel
class CreateAgentViewModel @Inject constructor(
    private val api: AgentsApi,
    private val webchat: WebchatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateAgentUiState())
    val state: StateFlow<CreateAgentUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val presets = apiCall { api.presets() }
                    .unwrap("галерея ролей")
                    .filter { it.enabled }
                    .sortedBy { it.sortOrder }
                _state.update { it.copy(presets = presets, loading = false) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().message) }
            }
        }
    }

    fun select(preset: AgentPresetDto) {
        _state.update {
            it.copy(
                selected = preset,
                name = preset.title?.takeIf { title -> title.isNotBlank() } ?: preset.name,
                instructions = preset.instructions.orEmpty(),
                instructionsExpanded = false,
                createError = null,
            )
        }
    }

    fun backToGallery() {
        _state.update { it.copy(selected = null, createError = null) }
    }

    fun onNameChange(value: String) {
        _state.update { it.copy(name = value) }
    }

    fun onInstructionsChange(value: String) {
        _state.update { it.copy(instructions = value) }
    }

    fun toggleInstructions() {
        _state.update { it.copy(instructionsExpanded = !it.instructionsExpanded) }
    }

    /**
     * Отдельного «создать из пресета» на бэкенде нет — запрос собирается здесь. `presetName`
     * проверяется сервером: неизвестное значение даёт 400.
     *
     * Кнопка завершения ведёт сразу в чат, а не обратно в список, поэтому здесь же создаётся первая
     * переписка.
     */
    fun create(onCreated: (CreatedAgent) -> Unit) {
        val current = _state.value
        val preset = current.selected ?: return
        if (current.creating || current.name.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(creating = true, createError = null) }
            try {
                val created = apiCall {
                    api.createAgent(
                        CreateAgentRequest(
                            name = current.name.trim(),
                            description = preset.description,
                            instructions = current.instructions,
                            // У пресета тип может быть не задан. Тогда это агент, чей «мозг» живёт
                            // на платформе, — GENERIC.
                            type = preset.agentType ?: DEFAULT_AGENT_TYPE,
                            skillIds = preset.skills.map { it.id },
                            presetName = preset.name,
                        )
                    )
                }.unwrap("создание агента")

                // Ответ содержит ещё и fullKey — программный ключ агента. Мессенджеру он не нужен:
                // не показываем и не храним.
                val agent = created.agent
                    ?: throw IllegalStateException("Сервер не вернул созданного агента")

                val session = webchat.startSession(agent.id)
                _state.update { it.copy(creating = false) }
                onCreated(
                    CreatedAgent(
                        agentId = agent.id,
                        agentName = agent.name.ifBlank { current.name.trim() },
                        sessionId = session.sessionId,
                    )
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(creating = false, createError = e.toApiException().message) }
            }
        }
    }

    private companion object {
        const val DEFAULT_AGENT_TYPE = "GENERIC"
    }
}
