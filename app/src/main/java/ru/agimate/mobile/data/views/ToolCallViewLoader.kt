package ru.agimate.mobile.data.views

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.ui.text.UiText
import javax.inject.Inject
import javax.inject.Singleton

/** Что удалось получить для вызова: страницу, данные или причину отказа. */
sealed interface ToolCallView {

    /** Страница и данные вызова есть; `call.result` может быть `null`, пока вызов идёт. */
    data class Ready(val call: SessionToolCall, val content: AgentViewContent) : ToolCallView

    /** Вью нет: вызов `404/403`, у тула нет `ui://` или привязку отозвали. */
    data object NoView : ToolCallView

    /** Страницы больше нет (`404`): привязку отозвали между вызовом и раскрытием. */
    data object PageGone : ToolCallView

    /** Не удалось получить данные вызова; карточка показывает ошибку с повтором. */
    data class CallFailed(val message: UiText) : ToolCallView

    /** Не удалось получить страницу (`502` и прочее); карточка показывает ошибку с повтором. */
    data class PageFailed(val message: UiText) : ToolCallView
}

/** Результат перечитывания данных открытой карточки. */
sealed interface ToolCallRefresh {
    data class Updated(val call: SessionToolCall) : ToolCallRefresh
    data object Gone : ToolCallRefresh
    data object Failed : ToolCallRefresh
}

/**
 * Узкий вход к загрузке вью вызова. Контроллер карточек и экран полного просмотра зависят от
 * этого интерфейса, а не от репозитория целиком: в тестах подменяется без сети.
 *
 * У карточки уже есть дескриптор, поэтому оба запроса идут параллельно. У полного экрана в
 * маршруте только id — он сначала получает вызов, потом страницу.
 */
interface ToolCallViewSource {
    suspend fun load(agentId: String, sessionId: String, descriptor: ToolCallDescriptor): ToolCallView
    suspend fun load(agentId: String, sessionId: String, toolCallId: String): ToolCallView
    suspend fun refreshCall(sessionId: String, toolCallId: String): ToolCallRefresh
}

@Singleton
class ToolCallViewLoader @Inject constructor(
    private val views: ViewsRepository,
) : ToolCallViewSource {

    override suspend fun load(
        agentId: String,
        sessionId: String,
        descriptor: ToolCallDescriptor,
    ): ToolCallView = coroutineScope {
        val connectionId = descriptor.connectionId
        val viewUri = descriptor.viewUri
        if (connectionId == null || viewUri == null) {
            return@coroutineScope ToolCallView.NoView
        }

        // Страница адресуется дескриптором, поэтому оба запроса стартуют вместе.
        val callDeferred = async { capture { views.toolCall(sessionId, descriptor.toolCallId) } }
        val contentDeferred = async { capture { views.content(agentId, connectionId, viewUri) } }

        val callResult = callDeferred.await()
        val contentResult = contentDeferred.await()
        combine(callResult, contentResult)
    }

    override suspend fun load(
        agentId: String,
        sessionId: String,
        toolCallId: String,
    ): ToolCallView {
        val callResult = capture { views.toolCall(sessionId, toolCallId) }
        val call = callResult.getOrNull()
            ?: return callFailure(callResult.exceptionOrNull())
        val connectionId = call.connectionId
        val viewUri = call.viewUri
        if (connectionId == null || viewUri == null) return ToolCallView.NoView

        val contentResult = capture { views.content(agentId, connectionId, viewUri) }
        return combine(Result.success(call), contentResult)
    }

    override suspend fun refreshCall(sessionId: String, toolCallId: String): ToolCallRefresh =
        try {
            ToolCallRefresh.Updated(views.toolCall(sessionId, toolCallId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            when (error) {
                is ApiException.NotFound, is ApiException.Forbidden -> ToolCallRefresh.Gone
                else -> ToolCallRefresh.Failed
            }
        }

    private fun combine(
        callResult: Result<SessionToolCall>,
        contentResult: Result<AgentViewContent>,
    ): ToolCallView {
        val call = callResult.getOrNull()
            ?: return callFailure(callResult.exceptionOrNull())
        if (call.viewUri == null || call.connectionId == null) return ToolCallView.NoView

        val content = contentResult.getOrNull()
            ?: return pageFailure(contentResult.exceptionOrNull())
        return ToolCallView.Ready(call, content)
    }

    private fun callFailure(error: Throwable?): ToolCallView = when (error) {
        is ApiException.NotFound, is ApiException.Forbidden -> ToolCallView.NoView
        null -> ToolCallView.NoView
        else -> ToolCallView.CallFailed(error.toApiException().text)
    }

    private fun pageFailure(error: Throwable?): ToolCallView = when (error) {
        is ApiException.NotFound -> ToolCallView.PageGone
        is ApiException.Forbidden -> ToolCallView.NoView
        null -> ToolCallView.PageGone
        else -> ToolCallView.PageFailed(error.toApiException().text)
    }

    /** `CancellationException` — не ошибка загрузки: её обязан пережить вызывающий. */
    private suspend fun <T> capture(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
