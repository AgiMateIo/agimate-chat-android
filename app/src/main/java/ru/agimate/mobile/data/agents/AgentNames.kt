package ru.agimate.mobile.data.agents

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Имена агентов, переименованных за эту жизнь процесса.
 *
 * Экраны переписок и чата получают имя агента маршрутом, а правка агента живой строки не порождает.
 * Без этого места под переименовавшим чатом оставались бы со старым именем экран переписок и чат,
 * из которого тот открыт: в стеке их бывает сколько угодно. Хранится только в памяти — после
 * перезапуска имя и так приходит с сервера.
 */
@Singleton
class AgentNames @Inject constructor() {

    private val renamed = MutableStateFlow<Map<String, String>>(emptyMap())

    fun put(agentId: String, name: String) {
        renamed.update { it + (agentId to name) }
    }

    /** Новое имя агента — только если его переименовали; исходное у экрана своё. */
    fun of(agentId: String): Flow<String> =
        renamed.map { it[agentId] }.filterNotNull().distinctUntilChanged()
}
