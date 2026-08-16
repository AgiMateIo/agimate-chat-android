package ru.agimate.mobile.core.realtime

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Какая переписка открыта прямо сейчас.
 *
 * Если чат открыт, то же сообщение придёт дважды: в канал переписки и в личный канал как
 * `webchat_activity`. Второе нужно только для счётчика — и счётчик открытой сейчас сессии расти не
 * должен. Экраны об этом друг другу не расскажут: у списка контактов и у чата разные ViewModel,
 * живущие в разных точках навигации.
 */
@Singleton
class OpenChatTracker @Inject constructor() {

    private val current = AtomicReference<String?>(null)

    val openSessionId: String? get() = current.get()

    fun open(sessionId: String) {
        current.set(sessionId)
    }

    fun close(sessionId: String) {
        current.compareAndSet(sessionId, null)
    }
}
