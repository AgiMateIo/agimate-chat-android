package ru.agimate.mobile.core.realtime

import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.ConnectedEvent
import io.github.centrifugal.centrifuge.ConnectingEvent
import io.github.centrifugal.centrifuge.ConnectionTokenEvent
import io.github.centrifugal.centrifuge.ConnectionTokenGetter
import io.github.centrifugal.centrifuge.DisconnectedEvent
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.SubscriptionOptions
import io.github.centrifugal.centrifuge.SubscriptionTokenEvent
import io.github.centrifugal.centrifuge.SubscriptionTokenGetter
import io.github.centrifugal.centrifuge.TokenCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.agimate.mobile.core.di.ApplicationScope
import kotlinx.serialization.json.decodeFromJsonElement
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.data.webchat.WebchatRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time поверх Centrifugo.
 *
 * Клиент не может подписаться сам: и подключение, и каждая подписка требуют серверного токена.
 * Токены живут час, поэтому вместо ручного таймера отданы `TokenGetter`'ы — библиотека сама
 * попросит свежий и при истечении, и при реконнекте.
 *
 * Каналов два и роли у них разные:
 *  - `user:{userId}` — одна подписка на всё приложение, живёт всегда, поднимает бейджи;
 *  - `webchat:{sessionId}` — подписка на время открытого чата, оттуда приходят сами сообщения.
 */
@Singleton
class RealtimeClient @Inject constructor(
    private val repository: WebchatRepository,
    private val urls: RealtimeUrl,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(RealtimeStatus.Idle)
    val status: StateFlow<RealtimeStatus> = _status.asStateFlow()

    private val _activity = MutableSharedFlow<WebchatActivityPayload>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val activity: SharedFlow<WebchatActivityPayload> = _activity.asSharedFlow()

    private val lock = Mutex()
    private var client: Client? = null
    private var userSubscription: Subscription? = null

    /** Поднять соединение и подписку на личный канал. Идемпотентно. */
    fun start() {
        scope.launch { ensureConnected() }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                userSubscription?.let { runCatching { it.unsubscribe() } }
                userSubscription = null
                client?.let { runCatching { it.disconnect() } }
                client = null
                _status.value = RealtimeStatus.Idle
            }
        }
    }

    /**
     * Подписка на канал переписки — живёт ровно столько, сколько собирают этот поток.
     *
     * У канала включены история и восстановление: после реконнекта Centrifugo досылает пропущенное,
     * поэтому перечитывать историю HTTP-запросом после каждого разрыва не нужно.
     */
    fun sessionMessages(sessionId: String): Flow<WebchatMessagePayload> = callbackFlow {
        val connected = ensureConnected()
        if (connected == null) {
            close()
            return@callbackFlow
        }

        val channel = "webchat:$sessionId"
        val options = SubscriptionOptions().apply {
            setPositioned(true)
            setRecoverable(true)
            tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent, cb: TokenCallback) {
                    provideToken(cb) { repository.sessionChannelToken(sessionId).subscriptionToken }
                }
            }
        }

        val listener = object : SubscriptionEventListener() {
            override fun onPublication(sub: Subscription, event: PublicationEvent) {
                decode<WebchatMessagePayload>(event, RealtimeEventType.MESSAGE)?.let { trySend(it) }
            }
        }

        val subscription = runCatching {
            connected.newSubscription(channel, options, listener)
        }.getOrElse {
            // Подписка на этот канал уже существует — переиспользуем её.
            connected.getSubscription(channel)
        }

        if (subscription == null) {
            close()
            return@callbackFlow
        }
        subscription.subscribe()

        awaitClose {
            runCatching { subscription.unsubscribe() }
            runCatching { connected.removeSubscription(subscription) }
        }
    }

    private suspend fun ensureConnected(): Client? = lock.withLock {
        client?.let { return it }

        _status.value = RealtimeStatus.Connecting

        // Первый токен нужен до создания клиента: из этого же ответа берётся адрес WebSocket.
        val bootstrap = runCatching { repository.userChannelToken() }.getOrElse {
            _status.value = RealtimeStatus.Disconnected
            return null
        }

        val options = Options().apply {
            token = bootstrap.connectionToken
            tokenGetter = object : ConnectionTokenGetter() {
                override fun getConnectionToken(event: ConnectionTokenEvent, cb: TokenCallback) {
                    provideToken(cb) { repository.userChannelToken().connectionToken }
                }
            }
        }

        val created = Client(urls.resolve(bootstrap.wsUrl), options, connectionListener())
        created.connect()

        val userOptions = SubscriptionOptions().apply {
            token = bootstrap.subscriptionToken
            tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent, cb: TokenCallback) {
                    provideToken(cb) { repository.userChannelToken().subscriptionToken }
                }
            }
        }

        val userListener = object : SubscriptionEventListener() {
            override fun onPublication(sub: Subscription, event: PublicationEvent) {
                decode<WebchatActivityPayload>(event, RealtimeEventType.ACTIVITY)
                    ?.let { _activity.tryEmit(it) }
            }
        }

        userSubscription = runCatching {
            created.newSubscription(bootstrap.channel, userOptions, userListener)
                .also { it.subscribe() }
        }.getOrNull()

        client = created
        created
    }

    private fun connectionListener() = object : EventListener() {
        override fun onConnected(client: Client, event: ConnectedEvent) {
            _status.value = RealtimeStatus.Connected
        }

        override fun onConnecting(client: Client, event: ConnectingEvent) {
            _status.value = RealtimeStatus.Connecting
        }

        override fun onDisconnected(client: Client, event: DisconnectedEvent) {
            _status.value = RealtimeStatus.Disconnected
        }
    }

    /**
     * Библиотека зовёт TokenGetter со своего потока и ждёт колбэка. Сходить за токеном надо по
     * HTTP, поэтому запускаем корутину в области приложения и отвечаем, когда придёт ответ.
     */
    private fun provideToken(cb: TokenCallback, fetch: suspend () -> String) {
        scope.launch {
            runCatching { fetch() }
                .onSuccess { cb.Done(null, it) }
                .onFailure { cb.Done(it, null) }
        }
    }

    private inline fun <reified T> decode(event: PublicationEvent, expectedType: String): T? =
        runCatching {
            val envelope = ApiJson.decodeFromString(
                RealtimeEnvelope.serializer(),
                String(event.data, Charsets.UTF_8),
            )
            if (envelope.type != expectedType) return null
            val payload = envelope.payload ?: return null
            ApiJson.decodeFromJsonElement<T>(payload)
        }.getOrNull()
}
