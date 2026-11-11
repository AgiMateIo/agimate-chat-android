package ru.agimate.mobile.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.R
import ru.agimate.mobile.core.realtime.SessionToolCallPayloadDto
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.views.SessionToolCall
import ru.agimate.mobile.data.views.ToolCallDescriptor
import ru.agimate.mobile.data.views.ToolCallRefresh
import ru.agimate.mobile.data.views.ToolCallView
import ru.agimate.mobile.data.views.ToolCallViewSource
import ru.agimate.mobile.data.webchat.ChatMessage

/**
 * Состояние inline-карточек одной открытой переписки.
 *
 * Не ViewModel: единственная зависимость от Android — строки ошибок, всё остальное — чистый
 * Kotlin, который проверяется без Robolectric. `ChatViewModel` создаёт контроллер, передаёт ему
 * `agentId`, сигналы и живые сообщения, а его `StateFlow` складывает в `ChatUiState`.
 *
 * Одна job и одно поколение на карточку. Свернули, сняли или переоткрыли — поколение выросло, и
 * любой поздний ответ загрузчика отбрасывается: карточка не «оживает» и HTML не возвращается в
 * состояние после того, как его отпустили.
 */
class InlineViewsController(
    private val sessionId: String,
    private val source: ToolCallViewSource,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(InlineViewsState())
    val state: StateFlow<InlineViewsState> = _state.asStateFlow()

    private var agentId: String? = null
    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Int>()

    /** История без дескрипторов: id, ждущие резолва, и уже запрошенные. */
    private val pendingHistory = LinkedHashSet<String>()
    private val requestedHistory = mutableSetOf<String>()

    /** id, про которые уже сказал сигнал: поздний ответ fallback не должен их перетирать. */
    private val signalIds = mutableSetOf<String>()

    /** id, разрешённые совместимым путём: резолв истории свежее и снимает их переопределение. */
    private val fallbackIds = mutableSetOf<String>()

    /** Агент переписки. Пока он неизвестен, карточки не рисуются и страница не запрашивается. */
    fun setAgentId(agentId: String?) {
        this.agentId = agentId
        resolvePendingHistory()
    }

    /**
     * Страница истории. Если бэкенд не резолвил дескрипторы (`toolCalls == null` — старый сервер,
     * сбой), каждый id один раз разрешается эндпойнтом вызова: это совместимый путь, в обычной
     * работе он не выполняется. Дескрипторы сигнала главнее — их ответ не перетираем.
     */
    fun onHistory(messages: List<ChatMessage>) {
        // Резолв истории свежее fallback-ответа: его переопределение снимается, и карточка
        // читает актуальный дескриптор строки. Сигнал при этом не трогаем — он главнее обоих.
        val resolved = messages.asSequence()
            .filter { it.toolCalls != null }
            .flatMap { it.toolCallIds.orEmpty().asSequence() }
            .filter { it in fallbackIds && it !in signalIds }
            .toList()
        if (resolved.isNotEmpty()) {
            fallbackIds.removeAll(resolved.toSet())
            _state.update { it.copy(overrides = it.overrides - resolved.toSet()) }
        }

        val ids = messages.asSequence()
            .filter { it.toolCalls == null }
            .flatMap { it.toolCallIds.orEmpty().asSequence() }
            .filter { it !in requestedHistory && it !in signalIds }
            .toList()
        if (ids.isEmpty()) return
        pendingHistory += ids
        resolvePendingHistory()
    }

    /**
     * `session.tool_call` открытой сессии. Дескрипторы сигнала главнее исторических: в них —
     * текущее состояние привязок.
     */
    fun onSignal(payload: SessionToolCallPayloadDto) {
        val descriptors = payload.toolCalls ?: return
        for (dto in descriptors) {
            val descriptor = ToolCallDescriptor.from(dto)
            signalIds += descriptor.toolCallId
            val card = _state.value.cards[descriptor.toolCallId]
            val addressChanged = card != null && (
                card.descriptor.viewUri != descriptor.viewUri ||
                    card.descriptor.connectionId != descriptor.connectionId
                )

            _state.update {
                it.copy(overrides = it.overrides + (descriptor.toolCallId to descriptor))
            }

            // Без вью или без коннекции карточку нечем адресовать: снимаем вместе с HTML.
            if (descriptor.viewUri == null || descriptor.connectionId == null) {
                discard(descriptor.toolCallId)
                continue
            }

            _state.update { it.copy(withdrawn = it.withdrawn - descriptor.toolCallId) }
            if (card != null) {
                _state.update {
                    it.copy(
                        cards = it.cards + (
                            descriptor.toolCallId to card.copy(descriptor = descriptor)
                            ),
                    )
                }
                refreshForSignal(descriptor, addressChanged)
            }
        }
    }

    /**
     * Любое живое сообщение открытой сессии. Detached-вызов мог осесть без сигнала, и следующий
     * ход — единственный повод это заметить.
     */
    fun onSessionMessage() {
        _state.value.cards.values
            .filter { it.phase is InlineViewPhase.AwaitingResult }
            .forEach { refreshCall(it.descriptor.toolCallId) }
    }

    /** Тап по карточке. Повторный тап по раскрытой ничего не перезапускает. */
    fun expand(descriptor: ToolCallDescriptor) {
        if (agentId == null) return
        if (descriptor.viewUri == null || descriptor.connectionId == null) return
        val card = _state.value.cards[descriptor.toolCallId]
        if (card != null &&
            card.phase !is InlineViewPhase.Failed &&
            card.phase != InlineViewPhase.Unavailable
        ) {
            return
        }
        startLoad(descriptor)
    }

    /** Свернуть: WebView уничтожается, HTML и данные вызова освобождаются. */
    fun collapse(toolCallId: String) {
        cancelJob(toolCallId)
        nextGeneration(toolCallId)
        _state.update { it.copy(cards = it.cards - toolCallId) }
    }

    /** Повтор после ошибки — та же загрузка заново. */
    fun retry(descriptor: ToolCallDescriptor) {
        startLoad(descriptor)
    }

    /** Рендерер WebView упал: карточка остаётся с ошибкой и повтором, приложение живо. */
    fun onRendererGone(toolCallId: String) {
        cancelJob(toolCallId)
        val card = _state.value.cards[toolCallId] ?: return
        _state.update {
            it.copy(
                cards = it.cards + (
                    toolCallId to card.copy(
                        phase = InlineViewPhase.Failed(uiText(R.string.views_crashed)),
                    )
                    ),
            )
        }
    }

    /** Уход с переписки: ни одной живой job и ни одного байта HTML. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        generations.clear()
        pendingHistory.clear()
        requestedHistory.clear()
        signalIds.clear()
        fallbackIds.clear()
        agentId = null
        _state.value = InlineViewsState()
    }

    /** Разрешить исторические id, если агент уже известен: по одному запросу вызова на id. */
    private fun resolvePendingHistory() {
        if (agentId == null) return
        val ids = pendingHistory.toList()
        pendingHistory.clear()
        for (id in ids) {
            if (!requestedHistory.add(id) || id in signalIds) continue
            scope.launch {
                when (val refresh = source.refreshCall(sessionId, id)) {
                    is ToolCallRefresh.Updated -> applyHistory(id, refresh.call)
                    ToolCallRefresh.Gone -> applyHistory(id, null)
                    // Сбой сети — не приговор: следующий снимок истории попробует снова.
                    ToolCallRefresh.Failed -> requestedHistory.remove(id)
                }
            }
        }
    }

    /** Дескриптор из совместимого пути; `call == null` — вью нет, карточка не появится. */
    private fun applyHistory(toolCallId: String, call: SessionToolCall?) {
        if (toolCallId in signalIds) return
        fallbackIds += toolCallId
        val descriptor = ToolCallDescriptor(
            toolCallId = toolCallId,
            name = call?.name.orEmpty(),
            connectionId = call?.connectionId,
            viewUri = call?.viewUri,
        )
        _state.update { it.copy(overrides = it.overrides + (toolCallId to descriptor)) }
    }

    private fun refreshForSignal(descriptor: ToolCallDescriptor, addressChanged: Boolean) {
        val card = _state.value.cards[descriptor.toolCallId] ?: return
        // Адрес сменился — даже идущая загрузка относится к старой странице и отменяется.
        if (addressChanged) {
            startLoad(descriptor)
            return
        }
        if (card.phase is InlineViewPhase.Loading) return
        when (card.phase) {
            is InlineViewPhase.Ready, is InlineViewPhase.AwaitingResult ->
                refreshCall(descriptor.toolCallId)
            // Держать нечего — страницы нет: сигнал даёт повод загрузить её заново.
            is InlineViewPhase.Failed, InlineViewPhase.Unavailable -> startLoad(descriptor)
            else -> Unit
        }
    }

    private fun refreshCall(toolCallId: String) {
        if (jobs[toolCallId]?.isActive == true) return
        val card = _state.value.cards[toolCallId] ?: return
        val content = when (val phase = card.phase) {
            is InlineViewPhase.AwaitingResult -> phase.content
            is InlineViewPhase.Ready -> phase.content
            else -> return
        }
        val generation = generations[toolCallId] ?: 0

        jobs[toolCallId] = scope.launch {
            val refresh = source.refreshCall(sessionId, toolCallId)
            if (!isCurrent(toolCallId, generation)) return@launch

            when (refresh) {
                is ToolCallRefresh.Updated -> {
                    val current = _state.value.cards[toolCallId] ?: return@launch
                    val call = refresh.call
                    if (call.viewUri == null || call.connectionId == null) {
                        _state.update {
                            it.copy(
                                cards = it.cards + (
                                    toolCallId to current.copy(
                                        phase = InlineViewPhase.Unavailable,
                                    )
                                    ),
                            )
                        }
                    } else {
                        val phase = if (call.result == null) {
                            InlineViewPhase.AwaitingResult(content, call)
                        } else {
                            InlineViewPhase.Ready(content, call)
                        }
                        _state.update {
                            it.copy(
                                cards = it.cards + (
                                    toolCallId to current.copy(
                                        descriptor = current.descriptor.copy(
                                            name = call.name,
                                            connectionId = call.connectionId,
                                            viewUri = call.viewUri,
                                        ),
                                        phase = phase,
                                    )
                                    ),
                            )
                        }
                    }
                }

                ToolCallRefresh.Gone -> {
                    val current = _state.value.cards[toolCallId] ?: return@launch
                    _state.update {
                        it.copy(
                            cards = it.cards + (
                                toolCallId to current.copy(phase = InlineViewPhase.Unavailable)
                                ),
                        )
                    }
                }

                ToolCallRefresh.Failed -> Unit
            }
        }
    }

    private fun startLoad(descriptor: ToolCallDescriptor) {
        val agent = agentId ?: return
        if (descriptor.connectionId == null || descriptor.viewUri == null) return

        val toolCallId = descriptor.toolCallId
        cancelJob(toolCallId)
        val generation = nextGeneration(toolCallId)
        val revision = _state.value.cards[toolCallId]?.revision ?: 0
        _state.update {
            it.copy(
                cards = it.cards + (
                    toolCallId to InlineCardState(
                        descriptor = descriptor,
                        phase = InlineViewPhase.Loading,
                        revision = revision,
                    )
                    ),
            )
        }

        jobs[toolCallId] = scope.launch {
            val result = source.load(agent, sessionId, descriptor)
            if (!isCurrent(toolCallId, generation)) return@launch
            applyLoad(toolCallId, result)
        }
    }

    private fun applyLoad(toolCallId: String, result: ToolCallView) {
        val card = _state.value.cards[toolCallId] ?: return
        when (result) {
            is ToolCallView.Ready -> {
                val phase = if (result.call.result == null) {
                    InlineViewPhase.AwaitingResult(result.content, result.call)
                } else {
                    InlineViewPhase.Ready(result.content, result.call)
                }
                _state.update {
                    it.copy(
                        cards = it.cards + (
                            toolCallId to card.copy(
                                phase = phase,
                                revision = card.revision + 1,
                            )
                            ),
                    )
                }
            }

            ToolCallView.NoView ->
                _state.update {
                    it.copy(
                        cards = it.cards + (
                            toolCallId to card.copy(phase = InlineViewPhase.Unavailable)
                            ),
                    )
                }

            ToolCallView.PageGone -> withdraw(toolCallId)

            is ToolCallView.CallFailed ->
                _state.update {
                    it.copy(
                        cards = it.cards + (
                            toolCallId to card.copy(
                                phase = InlineViewPhase.Failed(result.message),
                            )
                            ),
                    )
                }

            is ToolCallView.PageFailed ->
                _state.update {
                    it.copy(
                        cards = it.cards + (
                            toolCallId to card.copy(
                                phase = InlineViewPhase.Failed(result.message),
                            )
                            ),
                    )
                }
        }
    }

    /** Страница `404`: привязку отозвали. Сигнал с `viewUri` вернёт карточку. */
    private fun withdraw(toolCallId: String) {
        cancelJob(toolCallId)
        nextGeneration(toolCallId)
        _state.update {
            it.copy(cards = it.cards - toolCallId, withdrawn = it.withdrawn + toolCallId)
        }
    }

    /** Сигнал сказал «вью нет»: карточка снимается, но остаётся возможность её вернуть. */
    private fun discard(toolCallId: String) {
        cancelJob(toolCallId)
        nextGeneration(toolCallId)
        _state.update { it.copy(cards = it.cards - toolCallId) }
    }

    private fun cancelJob(toolCallId: String) {
        jobs.remove(toolCallId)?.cancel()
    }

    private fun nextGeneration(toolCallId: String): Int {
        val next = (generations[toolCallId] ?: 0) + 1
        generations[toolCallId] = next
        return next
    }

    private fun isCurrent(toolCallId: String, generation: Int): Boolean =
        generations[toolCallId] == generation && _state.value.cards.containsKey(toolCallId)
}
