package ru.agimate.mobile.data.agents

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import ru.agimate.mobile.core.network.ApiEnvelope

@Serializable
data class PresetSkillDto(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
)

/** Пресет роли — заготовка мастера создания: готовый текст инструкции и навыки, которые привязать. */
@Serializable
data class AgentPresetDto(
    val id: String,
    /** Стабильный слаг вида `personal-assistant`. */
    val name: String,
    val title: String? = null,
    val description: String? = null,
    /** Готовый текст на 1500–3000 знаков в markdown. */
    val instructions: String? = null,
    val skills: List<PresetSkillDto> = emptyList(),
    val connectorCodes: List<String> = emptyList(),
    /** `null` — тип не задан пресетом, выбирает клиент. */
    val agentType: String? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
)

/**
 * Отдельного «создать из пресета» на бэкенде нет — запрос собирает приложение.
 *
 * `presetName` нужен для аналитики воронки, но проверяется: неизвестное значение — 400.
 */
@Serializable
data class CreateAgentRequest(
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val type: String? = null,
    val skillIds: List<String> = emptyList(),
    val presetName: String? = null,
)

@Serializable
data class AgentDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
)

/**
 * Ответ создания. Поле `fullKey` — программный ключ агента; мессенджеру он не нужен, показывать и
 * хранить его нельзя. Идентификатор нового агента лежит внутри `agent`, а не в корне.
 */
@Serializable
data class AgentCreatedDto(
    val agent: AgentDto? = null,
)

/**
 * Открыть агенту коннектор. Внутренний коннектор называется кодом: инстанс у него один на
 * пользователя и до первой привязки у него ещё нет id, поэтому его подставляет сервер.
 */
@Serializable
data class BindConnectorRequest(val connectorCode: String)

@Serializable
data class AgentConnectionDto(
    val id: String? = null,
    val connectorCode: String? = null,
)

interface AgentsApi {

    /** Не постраничный список. Сортировать по `sortOrder`. */
    @GET("control/manage/agent-presets/")
    suspend fun presets(): ApiEnvelope<List<AgentPresetDto>>

    @POST("control/manage/agents/")
    suspend fun createAgent(@Body body: CreateAgentRequest): ApiEnvelope<AgentCreatedDto>

    /**
     * Привязка навыка ничего не открывает — навык лишь объявляет, с каким коннектором работает.
     * Пока коннектор не открыт этим вызовом, навык считается неудовлетворённым и агенту не отдаётся
     * **вовсе**: ни тулов, ни текста навыка. Идемпотентен: повтор возвращает ту же привязку.
     */
    @POST("control/manage/agents/{agentId}/connections/")
    suspend fun bindConnector(
        @Path("agentId") agentId: String,
        @Body body: BindConnectorRequest,
    ): ApiEnvelope<AgentConnectionDto>
}
