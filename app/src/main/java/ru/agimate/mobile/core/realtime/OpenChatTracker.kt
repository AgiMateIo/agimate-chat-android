package ru.agimate.mobile.core.realtime

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Какая переписка открыта прямо сейчас.
 *
 * Нужно уведомлениям: пуш об ответе в переписке, которую человек и так видит, — лишний. Экраны об
 * этом друг другу не расскажут: у чата и у канала уведомлений нет общей ViewModel.
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
