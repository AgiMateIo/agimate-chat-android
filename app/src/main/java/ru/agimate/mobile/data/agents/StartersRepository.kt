package ru.agimate.mobile.data.agents

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.unwrap
import javax.inject.Inject
import javax.inject.Singleton

/** Подсказка пустого чата — готовое первое сообщение, уходит одним нажатием. */
data class Starter(
    val text: String,
    /** Привязка навыка, от которого подсказка; `null` у своей подсказки агента. */
    val agentSkillId: String?,
    val skillTitle: String?,
) {
    /** Скрыть можно только подсказку навыка: свою агент убирает правкой списка. */
    val hideable: Boolean get() = agentSkillId != null

    /** Ключ в списке: свои тексты сервер держит уникальными, у навыка — уникальными в привязке. */
    val key: String get() = "${agentSkillId.orEmpty()}:$text"
}

data class Starters(val greeting: String?, val items: List<Starter>) {
    val isEmpty: Boolean get() = greeting.isNullOrBlank() && items.isEmpty()
}

@Singleton
class StartersRepository @Inject constructor(
    private val api: AgentsApi,
) {
    /**
     * Приветствие и подсказки для пустого чата. `null` — агенту они не положены: у `MCP` и
     * `WEBHOOK` мозг снаружи, и подсказка ушла бы туда, где её никто не ждёт.
     *
     * Сервер отдаёт подсказки любому агенту, а у контакта типа нет, — поэтому карточка агента
     * запрашивается рядом, параллельно.
     */
    suspend fun forChat(agentId: String): Starters? = coroutineScope {
        val agent = async { apiCall { api.agent(agentId) }.unwrap("карточка агента") }
        val starters = async { apiCall { api.starters(agentId) }.unwrap("подсказки агента") }
        if (agent.await().type?.uppercase() in EXTERNAL_TYPES) return@coroutineScope null
        val dto = starters.await()
        Starters(
            greeting = dto.greeting?.takeIf { it.isNotBlank() },
            items = dto.starters.filter { it.text.isNotBlank() }.map {
                Starter(text = it.text, agentSkillId = it.agentSkillId, skillTitle = it.skillTitle)
            },
        )
    }

    /**
     * Скрыть подсказку навыка. Повтор — не ошибка, а `404` значит, что у навыка её уже нет:
     * итог для человека тот же, и ошибкой это не считается.
     */
    suspend fun hide(agentId: String, starter: Starter) {
        val agentSkillId = starter.agentSkillId ?: return
        try {
            apiCall { api.hideStarter(agentId, HideStarterRequest(agentSkillId, starter.text)) }
        } catch (_: ApiException.NotFound) {
            // Уже нет — убрать локально и всё.
        }
    }

    private companion object {
        val EXTERNAL_TYPES = setOf("MCP", "WEBHOOK")
    }
}
