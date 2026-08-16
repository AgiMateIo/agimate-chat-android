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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.di.ApplicationScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.network.NetworkMonitor
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
 *
 * Имена каналов приходят из ответов на запрос токенов и на клиенте не собираются: токен — это грант
 * ровно на тот канал, что назвал сервер, и разойдись имена, Centrifugo молча ответит «нет прав».
 */
@Singleton
class RealtimeClient @Inject constructor(
    private val repository: WebchatRepository,
    private val urls: RealtimeUrl,
    private val network: NetworkMonitor,
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

    /**
     * Текущий клиент.
     *
     * `@Volatile`, потому что с ним сверяются слушатели библиотеки со своих потоков: выброшенный
     * клиент досылает свои события уже после замены, и его `onDisconnected` иначе затирал бы
     * статус живого соединения.
     */
    @Volatile
    private var client: Client? = null
    private var userSubscription: Subscription? = null

    @Volatile
    private var connectJob: Job? = null

    /**
     * Живые каналы переписок по идентификатору переписки. На канал заводится ровно одна подписка,
     * сколько бы экранов её ни читали: подписка в Centrifugo одна на клиента, и второй читатель,
     * заведи он свою, получил бы `DuplicateSubscriptionException` и чужой listener — то есть тишину.
     *
     * Ключ — `sessionId`, а не имя канала: имя известно только после ответа сервера с токеном.
     */
    private val channels = mutableMapOf<String, SessionChannel>()

    /**
     * Канал одной переписки и его читатели.
     *
     * @param sessionId переписка, ради которой канал заведён — по ней берётся токен
     * @param name имя канала, как его назвал сервер
     */
    private class SessionChannel(val sessionId: String, val name: String) {
        /**
         * Replay закрывает щель между «подписка заведена» и «читатель прицепился» — доли
         * миллисекунды. На большее он не рассчитан и не нужен: пока читатель прицеплен,
         * буферизацией занимается `extraBufferCapacity`.
         */
        val messages = MutableSharedFlow<WebchatMessagePayload>(
            replay = 4,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val status = MutableStateFlow(RealtimeStatus.Connecting)

        /** Канал выброшен из реестра — читателям пора идти за новым. */
        val dropped = MutableStateFlow(false)

        var readers = 0
        var subscription: Subscription? = null

        /** Сколько раз подряд подписка не поднялась — от этого растёт пауза перед повтором. */
        var failedAttempts = 0
    }

    /**
     * Поднять соединение и подписку на личный канал. Идемпотентно.
     *
     * Неудача не заканчивает попытку: сеть могла отсутствовать на старте приложения, и без повтора
     * бейджи не поднялись бы до перезапуска.
     */
    fun start() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            var attempt = 0
            while (ensureConnected() == null) {
                awaitRetry(attempt++)
            }
        }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            lock.withLock {
                channels.values.forEach(::drop)
                channels.clear()
                userSubscription = null

                val dying = client
                // Обнулить до close: слушатель сверяется с этим полем, и события умирающего
                // клиента должны отсеяться как чужие.
                client = null
                _status.value = RealtimeStatus.Idle
                // close, а не disconnect: disconnect рвёт только соединение, оставляя живыми
                // executor, scheduler и пул соединений — каждый разлогин протекал бы ими.
                dying?.let { runCatching { it.close(0) } }
            }
        }
    }

    /** Снять подписку канала и объявить его выброшенным. Вызывать под [lock]. */
    private fun drop(channel: SessionChannel) {
        channel.subscription?.let { runCatching { it.unsubscribe() } }
        channel.subscription = null
        channel.status.value = RealtimeStatus.Disconnected
        channel.dropped.value = true
    }

    /**
     * События канала переписки — живут ровно столько, сколько собирают этот поток.
     *
     * У канала включены история и восстановление: после реконнекта Centrifugo досылает пропущенное,
     * поэтому перечитывать историю HTTP-запросом после каждого разрыва не нужно.
     *
     * Ни неудача с соединением, ни потеря самого канала поток не завершают: осечка не должна
     * оставлять открытый чат без живых сообщений до самого выхода с экрана.
     */
    fun sessionEvents(sessionId: String): Flow<SessionEvent> = flow {
        var attempt = 0
        while (true) {
            val channel = acquire(sessionId)
            if (channel == null) {
                emit(SessionEvent.Status(RealtimeStatus.Disconnected))
                awaitRetry(attempt++)
                continue
            }
            attempt = 0
            try {
                emitAll(channel.events())
            } finally {
                // Отпускать надо и при отмене — иначе счётчик читателей не сойдётся и канал
                // останется висеть подписанным до конца жизни процесса.
                withContext(NonCancellable) { release(channel) }
            }
            // Досюда доходим, только когда канал выбросили: идём за новым.
        }
    }

    /**
     * События канала, кончающиеся вместе с ним.
     *
     * Без явного признака выброшенности поток из `SharedFlow` и `StateFlow` не кончается никогда, и
     * читатель, чей канал сняли, молча висел бы на мёртвом до закрытия экрана.
     */
    private fun SessionChannel.events(): Flow<SessionEvent> = merge(
        messages.map { Signal.Event(SessionEvent.Message(it)) },
        status.map { Signal.Event(SessionEvent.Status(it)) },
        dropped.filter { it }.map { Signal.Dropped },
    ).transformWhile { signal ->
        if (signal is Signal.Event) emit(signal.event)
        signal is Signal.Event
    }

    private sealed interface Signal {
        data class Event(val event: SessionEvent) : Signal

        data object Dropped : Signal
    }

    /** Ещё один читатель канала; первый заводит подписку. `null` — не сложилось, стоит повторить. */
    private suspend fun acquire(sessionId: String): SessionChannel? {
        ensureConnected() ?: return null

        lock.withLock {
            channels[sessionId]?.let {
                it.readers++
                return it
            }
        }

        // За токеном ходим вне замка: это HTTP, и держать на нём мьютекс значит останавливать и
        // другие каналы, и переподключение. Имя канала берём отсюда же — токен выписан на него.
        val issued = runCatching { repository.sessionChannelToken(sessionId) }.getOrElse {
            warn(it) { "токен канала переписки: не получен" }
            return null
        }

        return lock.withLock {
            // Пока ходили за токеном, канал мог завести другой читатель.
            channels[sessionId]?.let {
                it.readers++
                return@withLock it
            }
            // Клиента перечитываем под замком: между ensureConnected и этой строкой мог пройти
            // stop(), и подписка ушла бы на уже выброшенный клиент — молча и навсегда.
            val live = client ?: return@withLock null

            val fresh = SessionChannel(sessionId, issued.channel)
            fresh.readers = 1
            channels[sessionId] = fresh
            subscribe(live, fresh, issued.subscriptionToken)
            fresh
        }
    }

    /** Читателем меньше; последний уносит с собой подписку. */
    private suspend fun release(channel: SessionChannel) = lock.withLock {
        channel.readers--
        if (channel.readers > 0) return@withLock

        // Сверка по экземпляру, а не по идентификатору: канал могли выбросить и завести заново, и
        // снимать чужую живую подписку нельзя.
        if (channels[channel.sessionId] === channel) {
            channels.remove(channel.sessionId)
        }
        channel.subscription?.let { subscription ->
            val live = client
            // removeSubscription отписывается сам; без клиента остаётся только отписка.
            runCatching {
                if (live != null) live.removeSubscription(subscription) else subscription.unsubscribe()
            }
        }
        channel.subscription = null
    }

    /**
     * Заводит подписку канала. Вызывать под [lock].
     *
     * @param token готовый токен подписки; `null` — пусть его добудет `tokenGetter` (так поднимают
     *              подписку заново: прежний токен к тому моменту обычно и есть причина падения)
     */
    private fun subscribe(client: Client, channel: SessionChannel, token: String?) {
        val options = SubscriptionOptions().apply {
            setPositioned(true)
            setRecoverable(true)
            if (token != null) this.token = token
            tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent, cb: TokenCallback) {
                    provideToken(cb, "токен ${channel.name}") {
                        val issued = repository.sessionChannelToken(channel.sessionId)
                        // Сервер сменил имя канала на лету — подписка уже заведена на прежнее, и
                        // свежий грант к ней не подойдёт.
                        if (issued.channel != channel.name) {
                            warn { "${channel.name}: токен выписан на ${issued.channel}" }
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
                    warn { "${channel.name}: публикация не разобрана — ${describe(event)}" }
                    return
                }
                trace { "${channel.name}: ${payload.stream} ${payload.messageId}" }
                channel.messages.tryEmit(payload)
            }

            override fun onSubscribed(sub: Subscription, event: SubscribedEvent) {
                trace { "${channel.name}: подписан, recovered=${event.recovered}" }
                channel.failedAttempts = 0
                channel.status.value = RealtimeStatus.Connected
            }

            override fun onSubscribing(sub: Subscription, event: SubscribingEvent) {
                trace { "${channel.name}: подписывается (${event.code}) ${event.reason}" }
                channel.status.value = RealtimeStatus.Connecting
            }

            override fun onError(sub: Subscription, event: SubscriptionErrorEvent) {
                warn(event.error) { "${channel.name}: ошибка подписки" }
            }

            /**
             * Библиотека сама повторяет только временные ошибки. На постоянной — «нет прав»,
             * негодный токен — подписка умирает молча и навсегда, а чат при живом WebSocket
             * выглядит работающим. Пока канал кому-то нужен, поднимаем его заново.
             */
            override fun onUnsubscribed(sub: Subscription, event: UnsubscribedEvent) {
                channel.status.value = RealtimeStatus.Disconnected
                warn { "${channel.name}: подписка снята (${event.code}) ${event.reason}" }
                scheduleResubscribe(channel)
            }
        }

        trace { "${channel.name}: завожу подписку" }
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
            warn { "${channel.name}: подписку завести не удалось" }
            // Без повтора канал остался бы в реестре с мёртвой подпиской: `onUnsubscribed` уже не
            // придёт — некому, — и поднять его было бы нечем.
            scheduleResubscribe(channel)
            return
        }
        subscription.subscribe()
    }

    /**
     * Поднять умершую подписку. Именно новой: старая держит тот же протухший токен — библиотека
     * чистит его лишь на части кодов, — и повторный `subscribe()` упёрся бы в ту же ошибку.
     *
     * Пауза растёт от попытки к попытке. Постоянная ошибка вроде «нет прав» иначе превращается в
     * бесконечный штурм раз в три секунды, каждый круг — с HTTP-запросом за токеном и разбуженным
     * радио. Успешная подписка обнуляет счёт.
     */
    private fun scheduleResubscribe(channel: SessionChannel) {
        val attempt = channel.failedAttempts++

        scope.launch {
            awaitRetry(attempt)
            lock.withLock {
                // Канал успели закрыть или пересоздать — восстанавливать нечего.
                if (channels[channel.sessionId] !== channel || channel.readers == 0) return@withLock

                val live = client
                if (live == null) {
                    // Соединения сейчас нет, но канал ещё нужен: сдаться здесь значит оставить
                    // открытый чат немым навсегда.
                    scheduleResubscribe(channel)
                    return@withLock
                }
                channel.subscription?.let { runCatching { live.removeSubscription(it) } }
                channel.subscription = null
                subscribe(live, channel, token = null)
            }
        }
    }

    /**
     * Пауза перед повтором: растёт вдвое от попытки к попытке, но обрывается вернувшейся сетью.
     *
     * Без второго условия чат после туннеля или лифта молчал бы ещё до минуты — интервал к тому
     * времени успевает дорасти до потолка, а связь уже есть.
     */
    private suspend fun awaitRetry(attempt: Int) {
        val backoff = (RETRY_DELAY_MS shl attempt.coerceIn(0, MAX_BACKOFF_SHIFT))
            .coerceAtMost(MAX_RETRY_DELAY_MS)
        withTimeoutOrNull(backoff) { network.becameAvailable.first() }
    }

    private suspend fun ensureConnected(): Client? = lock.withLock {
        client?.let { return it }

        _status.value = RealtimeStatus.Connecting

        // Первый токен нужен до создания клиента: из этого же ответа берутся адрес WebSocket и имя
        // личного канала.
        val bootstrap = runCatching { repository.userChannelToken() }.getOrElse {
            warn(it) { "токен личного канала: не получен" }
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
        trace { "подключаюсь к $wsUrl (сервер отдал ${bootstrap.wsUrl})" }
        val created = Client(wsUrl, options, connectionListener())
        // Присвоить до connect: слушатель сверяется с этим полем и отбросил бы как чужие
        // собственные события, пришедшие раньше присваивания.
        client = created
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
                trace { "${bootstrap.channel}: активность ${payload.stream} ${payload.messageId}" }
                _activity.tryEmit(payload)
            }

            override fun onSubscribed(sub: Subscription, event: SubscribedEvent) {
                trace { "${bootstrap.channel}: подписан" }
            }

            override fun onError(sub: Subscription, event: SubscriptionErrorEvent) {
                warn(event.error) { "${bootstrap.channel}: ошибка подписки" }
            }

            override fun onUnsubscribed(sub: Subscription, event: UnsubscribedEvent) {
                warn { "${bootstrap.channel}: подписка снята (${event.code}) ${event.reason}" }
            }
        }

        userSubscription = runCatching {
            created.newSubscription(bootstrap.channel, userOptions, userListener)
                .also { it.subscribe() }
        }.getOrNull()

        created
    }

    private fun connectionListener() = object : EventListener() {
        override fun onConnected(client: Client, event: ConnectedEvent) {
            if (!isCurrent(client)) return
            trace { "соединение установлено" }
            _status.value = RealtimeStatus.Connected
        }

        override fun onConnecting(client: Client, event: ConnectingEvent) {
            if (!isCurrent(client)) return
            trace { "подключаюсь (${event.code}) ${event.reason}" }
            _status.value = RealtimeStatus.Connecting
        }

        override fun onDisconnected(client: Client, event: DisconnectedEvent) {
            if (!isCurrent(client)) return
            warn { "соединение потеряно (${event.code}) ${event.reason}" }
            _status.value = RealtimeStatus.Disconnected
        }
    }

    /**
     * Свой ли это клиент. Выброшенный досылает события уже после замены, а статус общий — без
     * сверки его прощальное `onDisconnected` показывало бы потерю связи поверх живого соединения.
     */
    private fun isCurrent(candidate: Client): Boolean = candidate === client

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
                    warn(it) { "$what: не получен" }
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

    /**
     * Ход событий real-time — только для отладочной сборки.
     *
     * В релизе этих строк нет вовсе: они несут идентификаторы сессий и пользователя, logcat читает
     * кто угодно, а поводов туда смотреть у пользователя нет. Разбирать поломку по ним всё равно
     * может только тот, кто собирает debug.
     *
     * Сообщение приходит лямбдой, а функция `inline`: в релизе не тратится даже склейка строки.
     */
    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message())
    }

    /** То же для поломок: в релизе молчит по той же причине. */
    private inline fun warn(error: Throwable? = null, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val text = message()
        if (error != null) Log.w(TAG, text, error) else Log.w(TAG, text)
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

        /** Первая ступень паузы перед повтором — дальше удваивается. */
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_RETRY_DELAY_MS = 60_000L

        /** Потолок удвоений: дальше пауза упирается в [MAX_RETRY_DELAY_MS]. */
        const val MAX_BACKOFF_SHIFT = 5
    }
}
