package ru.agimate.mobile.core.push

import android.util.Log
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.realtime.OpenChatTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пуш-уведомления как целое: подъём транспорта и показ пришедшего.
 *
 * Живёт от `Application`, а не от экрана: пуш приходит и когда открытых экранов нет вовсе.
 *
 * Про подписку на сервере здесь нет ничего намеренно — ею владеет [PushSubscriptions], и токен от
 * SDK уходит туда как состояние, а не как повод сходить на сервер прямо отсюда.
 */
@Singleton
class PushSync @Inject constructor(
    private val transport: PushTransport,
    private val notifier: PushNotifier,
    private val openChats: OpenChatTracker,
    private val visibility: AppVisibility,
    private val dedup: PushDedup,
    private val subscriptions: PushSubscriptions,
) {
    fun start() {
        if (!transport.configured) return

        notifier.ensureChannel()
        transport.start(onMessage = ::onMessage, onToken = subscriptions::onTransportToken)
        subscriptions.start()
    }

    private fun onMessage(message: PushMessage) {
        if (!shouldShowPush(message, openChats.openSessionId, visibility.visible)) {
            trace { "пуш ${message.sessionId}: переписка открыта, молчим" }
            return
        }
        if (!dedup.isNew(message.messageId)) {
            trace { "пуш ${message.messageId}: уже показывали" }
            return
        }
        trace { "пуш ${message.sessionId}: показываю" }
        notifier.show(message)
    }

    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message())
    }

    private companion object {
        const val TAG = "AgiPush"
    }
}
