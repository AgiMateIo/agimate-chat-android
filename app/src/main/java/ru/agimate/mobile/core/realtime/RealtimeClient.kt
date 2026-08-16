package ru.agimate.mobile.core.realtime

import android.util.Log
import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.ConnectedEvent
import io.github.centrifugal.centrifuge.ConnectingEvent
import io.github.centrifugal.centrifuge.ConnectionTokenEvent
import io.github.centrifugal.centrifuge.ConnectionTokenGetter
import io.github.centrifugal.centrifuge.DisconnectedEvent
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.SubscribedEvent
import io.github.centrifugal.centrifuge.SubscribingEvent
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionErrorEvent
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.SubscriptionOptions
import io.github.centrifugal.centrifuge.SubscriptionTokenEvent
import io.github.centrifugal.centrifuge.SubscriptionTokenGetter
import io.github.centrifugal.centrifuge.TokenCallback
import io.github.centrifugal.centrifuge.UnsubscribedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.agimate.mobile.core.di.ApplicationScope
import kotlinx.serialization.json.JsonObject
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

    /**
     * Живые каналы переписок по имени канала. На канал заводится ровно одна подписка, сколько бы
     * экранов её ни читали: подписка в Centrifugo одна на клиента, и второй читатель, заведи он
     * свою, получил бы `DuplicateSubscriptionException` и чужой listener — то есть тишину.
     */
    private val channels = mutableMapOf<String, SessionChannel>()

    /**
     * Канал одной переписки и его читатели.
     *
     * @param sessionId переписка, ради которой канал заведён — по ней же берётся токен подписки
     */
    private class SessionChannel(val sessionId: String) {
        val name: String get() = channelName(sessionId)

        /**
         * Небольшой replay закрывает щель между «подписка заведена» и «читатель прицепился»:
         * повтор безопасен, ленту дедуплицирует `mergeLiveMessage`, а потеря — нет.
         */
        val messages = MutableSharedFlow<WebchatMessagePayload>(
            replay = 4,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val status = MutableStateFlow(RealtimeStatus.Connecting)

        var readers = 0
        var subscription: Subscription? = null
    }

    /** Поднять соединение и подписку на личный канал. Идемпотентно. */
    fun start() {
        scope.launch { ensureConnected() }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                channels.values.forEach { channel ->
                    channel.subscription?.let { runCatching { it.unsubscribe() } }
                    channel.subscription = null
                    channel.status.value = RealtimeStatus.Disconnected
                }
                channels.clear()
                userSubscription?.let { runCatching { it.unsubscribe() } }
                userSubscription = null
                client?.let { runCatching { it.disconnect() } }
                client = null
                _status.value = RealtimeStatus.Idle
            }
        }
    }

    /**
     * События канала переписки — живут ровно столько, сколько собирают этот поток.
     *
     * У канала включены история и восстановление: после реконнекта Centrifugo досылает пропущенное,
     * поэтому перечитывать историю HTTP-запросом после каждого разрыва не нужно.
     *
     * Неудача с соединением или токеном поток не завершает: одна осечка не должна оставлять
     * открытый чат без живых сообщений до самого выхода с экрана.
     */
    fun sessionEvents(sessionId: String): Flow<SessionEvent> = flow {
        while (true) {
            val channel = acquire(sessionId)
            if (channel == null) {
                emit(SessionEvent.Status(RealtimeStatus.Disconnected))
                delay(RETRY_DELAY_MS)
                continue
            }
            try {
                emitAll(
                    merge(
                        channel.messages.map(SessionEvent::Message),
                        channel.status.map(SessionEvent::Status),
                    )
                )
            } finally {
                // Отпускать надо и при отмене — иначе счётчик читателей не сойдётся и канал
                // останется висеть подписанным до конца жизни процесса.
                withContext(NonCancellable) { release(sessionId) }
            }
        }
    }

    /** Ещё один читатель канала; первый заводит подписку. `null` — соединения нет, стоит повторить. */
    private suspend fun acquire(sessionId: String): SessionChannel? {
        // Снаружи замка: ensureConnected берёт его сам.
        val connected = ensureConnected() ?: return null
        return lock.withLock {
            val existing = channels[channelName(sessionId)]
            if (existing != null) {
                existing.readers++
                return@withLock existing
            }
            val fresh = SessionChannel(sessionId)
            fresh.readers = 1
            channels[fresh.name] = fresh
            subscribe(connected, fresh)
            fresh
        }
    }

    /** Читателем меньше; последний уносит с собой подписку. */
    private suspend fun release(sessionId: String) = lock.withLock {
        val channel = channels[channelName(sessionId)] ?: return@withLock
        channel.readers--
        if (channel.readers > 0) return@withLock

        channels.remove(channel.name)
        val live = client
        channel.subscription?.let { subscription ->
            // removeSubscription отписывается сам; без клиента остаётся только отписка.
            runCatching {
                if (live != null) live.removeSubscription(subscription) else subscription.unsubscribe()
            }
        }
        channel.subscription = null
    }

    /** Заводит подписку канала. Вызывать под [lock]. */
    private fun subscribe(client: Client, channel: SessionChannel) {
        val options = SubscriptionOptions().apply {
            setPositioned(true)
            setRecoverable(true)
            tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent, cb: TokenCallback) {
                    provideToken(cb, "токен ${channel.name}") {
                        val issued = repository.sessionChannelToken(channel.sessionId)
                        // Имя канала строится на клиенте, а грант выписан на канал из ответа:
                        // разойдись они — Centrifugo ответит «нет прав», и чат замолчит.
                        if (issued.channel != channel.name) {
                            Log.w(TAG, "${channel.name}: токен выписан на ${issued.channel}")
                        }
                        issued.subscriptionToken
                    }
                }
            }
        }

        val listener = object : SubscriptionEventListener() {
            override fun onPublication(sub: Subscription, event: PublicationEvent) {
                val payload = decode<WebchatMessagePayload>(event, RealtimeEventType.MESSAGE)
                if (payload == null) {
                    // Не разобралось или это не webchat_message — иначе сообщение пропадёт молча.
                    Log.w(TAG, "${channel.name}: публикация не разобрана — ${describe(event)}")
                    return
                }
                Log.i(TAG, "${channel.name}: ${payload.stream} ${payload.messageId}")
                channel.messages.tryEmit(payload)
            }

            override fun onSubscribed(sub: Subscription, event: SubscribedEvent) {
                Log.i(TAG, "${channel.name}: подписан, recovered=${event.recovered}")
                channel.status.value = RealtimeStatus.Connected
            }

            override fun onSubscribing(sub: Subscription, event: SubscribingEvent) {
                Log.i(TAG, "${channel.name}: подписывается (${event.code}) ${event.reason}")
                channel.status.value = RealtimeStatus.Connecting
            }

            override fun onError(sub: Subscription, event: SubscriptionErrorEvent) {
                Log.w(TAG, "${channel.name}: ошибка подписки", event.error)
            }

            /**
             * Библиотека сама повторяет только временные ошибки. На постоянной — «нет прав»,
             * негодный токен — подписка умирает молча и навсегда, а чат при живом WebSocket
             * выглядит работающим. Пока канал кому-то нужен, поднимаем его заново.
             */
            override fun onUnsubscribed(sub: Subscription, event: UnsubscribedEvent) {
                channel.status.value = RealtimeStatus.Disconnected
                Log.w(TAG, "${channel.name}: подписка снята (${event.code}) ${event.reason}")
                scheduleResubscribe(channel)
            }
        }

        Log.i(TAG, "${channel.name}: завожу подписку")
        val subscription = runCatching { client.newSubscription(channel.name, options, listener) }
            .getOrElse {
                // В реестре осталась подписка от прошлой жизни канала. Своей рядом не завести, а
                // чужая пишет в чужой поток — снимаем и заводим заново.
                client.getSubscription(channel.name)?.let { stale ->
                    runCatching { client.removeSubscription(stale) }
                }
                runCatching { client.newSubscription(channel.name, options, listener) }.getOrNull()
            }

        channel.subscription = subscription
        if (subscription == null) {
            channel.status.value = RealtimeStatus.Disconnected
            Log.w(TAG, "Канал ${channel.name}: подписку завести не удалось")
            return
        }
        subscription.subscribe()
    }

    /**
     * Поднять умершую подписку. Именно новой: старая держит тот же протухший токен — библиотека
     * чистит его лишь на части кодов, — и повторный `subscribe()` упёрся бы в ту же ошибку.
     */
    private fun scheduleResubscribe(channel: SessionChannel) {
        scope.launch {
            delay(RETRY_DELAY_MS)
            lock.withLock {
                // Канал успели закрыть или пересоздать — восстанавливать нечего.
                if (channels[channel.name] !== channel || channel.readers == 0) return@withLock
                val live = client ?: return@withLock
                channel.subscription?.let { runCatching { live.removeSubscription(it) } }
                channel.subscription = null
                subscribe(live, channel)
            }
        }
    }

    private suspend fun ensureConnected(): Client? = lock.withLock {
        client?.let { return it }

        _status.value = RealtimeStatus.Connecting

        // Первый токен нужен до создания клиента: из этого же ответа берётся адрес WebSocket.
        val bootstrap = runCatching { repository.userChannelToken() }.getOrElse {
            Log.w(TAG, "токен личного канала: не получен", it)
            _status.value = RealtimeStatus.Disconnected
            return null
        }

        val options = Options().apply {
            token = bootstrap.connectionToken
            tokenGetter = object : ConnectionTokenGetter() {
                override fun getConnectionToken(event: ConnectionTokenEvent, cb: TokenCallback) {
                    provideToken(cb, "токен соединения") {
                        repository.userChannelToken().connectionToken
                    }
                }
            }
        }

        val wsUrl = urls.resolve(bootstrap.wsUrl)
        Log.i(TAG, "подключаюсь к $wsUrl (сервер отдал ${bootstrap.wsUrl})")
        val created = Client(wsUrl, options, connectionListener())
        created.connect()

        val userOptions = SubscriptionOptions().apply {
            token = bootstrap.subscriptionToken
            tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent, cb: TokenCallback) {
                    provideToken(cb, "токен личного канала") {
                        repository.userChannelToken().subscriptionToken
                    }
                }
            }
        }

        // Личный канал логируется наравне с каналом переписки намеренно: рядом видно, работает ли
        // соединение вообще, — иначе не отличить «сломан весь real-time» от «сломан один канал».
        val userListener = object : SubscriptionEventListener() {
            override fun onPublication(sub: Subscription, event: PublicationEvent) {
                val payload = decode<WebchatActivityPayload>(event, RealtimeEventType.ACTIVITY)
                if (payload == null) return
                Log.i(TAG, "${bootstrap.channel}: активность ${payload.stream} ${payload.messageId}")
                _activity.tryEmit(payload)
            }

            override fun onSubscribed(sub: Subscription, event: SubscribedEvent) {
                Log.i(TAG, "${bootstrap.channel}: подписан")
            }

            override fun onError(sub: Subscription, event: SubscriptionErrorEvent) {
                Log.w(TAG, "${bootstrap.channel}: ошибка подписки", event.error)
            }

            override fun onUnsubscribed(sub: Subscription, event: UnsubscribedEvent) {
                Log.w(TAG, "${bootstrap.channel}: подписка снята (${event.code}) ${event.reason}")
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
            Log.i(TAG, "соединение установлено")
            _status.value = RealtimeStatus.Connected
        }

        override fun onConnecting(client: Client, event: ConnectingEvent) {
            // Сюда же приходит бесконечный повтор при недоступном WebSocket: адрес взят из ответа
            // сервера, и промахнуться в нём можно, ничего не сломав в остальном API.
            Log.i(TAG, "подключаюсь (${event.code}) ${event.reason}")
            _status.value = RealtimeStatus.Connecting
        }

        override fun onDisconnected(client: Client, event: DisconnectedEvent) {
            Log.w(TAG, "соединение потеряно (${event.code}) ${event.reason}")
            _status.value = RealtimeStatus.Disconnected
        }
    }

    /**
     * Библиотека зовёт TokenGetter со своего потока и ждёт колбэка. Сходить за токеном надо по
     * HTTP, поэтому запускаем корутину в области приложения и отвечаем, когда придёт ответ.
     */
    private fun provideToken(cb: TokenCallback, what: String, fetch: suspend () -> String) {
        scope.launch {
            runCatching { fetch() }
                .onSuccess { cb.Done(null, it) }
                .onFailure {
                    // Библиотека молча уходит в повтор с backoff — без этой строки не видно,
                    // что подписка стоит именно на добыче токена.
                    Log.w(TAG, "$what: не получен", it)
                    cb.Done(it, null)
                }
        }
    }

    /**
     * Чем описать неразобранную публикацию. Текст переписки в лог не попадает — только тип события
     * и имена полей: этого хватает, чтобы понять, разошлись ли формы, а содержимое сообщений в
     * logcat читает кто угодно.
     */
    private fun describe(event: PublicationEvent): String {
        val envelope = runCatching {
            ApiJson.decodeFromString(
                RealtimeEnvelope.serializer(),
                String(event.data, Charsets.UTF_8),
            )
        }.getOrNull() ?: return "${event.data.size} Б, конверт не разобран"

        val fields = (envelope.payload as? JsonObject)?.keys?.joinToString(",").orEmpty()
        return "type=${envelope.type}, поля payload: $fields"
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

    private companion object {
        const val TAG = "Realtime"

        /** Пауза перед повтором — и для соединения, и для упавшей подписки. */
        const val RETRY_DELAY_MS = 3_000L

        fun channelName(sessionId: String) = "webchat:$sessionId"
    }
}
