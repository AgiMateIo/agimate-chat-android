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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.di.ApplicationScope
import ru.agimate.mobile.core.network.ApiJson
import ru.agimate.mobile.core.network.NetworkMonitor
import ru.agimate.mobile.data.webchat.WebchatContactDto
import ru.agimate.mobile.data.webchat.WebchatRepository
import ru.agimate.mobile.data.webchat.WebchatSessionDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time поверх Centrifugo.
 *
 * Клиент не может подписаться сам: и подключение, и подписка требуют серверного токена. Токены
 * живут час, поэтому вместо ручного таймера отданы `TokenGetter`'ы — библиотека сама попросит
 * свежий и при истечении, и при реконнекте.
 *
 * Канал один — личный `user:{userId}`, и подписка на него одна на всё приложение: оттуда приходят и
 * строки списков, и сами сообщения переписок. Экраны разбирают общий поток [events] сами, по типу
 * события и его ключу.
 *
 * Имя канала приходит из ответа на запрос токенов и на клиенте не собирается: токен — это грант
 * ровно на тот канал, что назвал сервер, и разойдись имена, Centrifugo молча ответит «нет прав».
 */
@Singleton
class RealtimeClient @Inject constructor(
    private val repository: WebchatRepository,
    private val urls: RealtimeUrl,
    private val network: NetworkMonitor,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val connectionStatus = MutableStateFlow(RealtimeStatus.Idle)
    private val subscriptionStatus = MutableStateFlow(RealtimeStatus.Idle)

    /**
     * Худшее из соединения и подписки. Живое событие доезжает, только когда целы обе половины, и
     * целый WebSocket с умершей подпиской не должен выглядеть как «на связи».
     */
    val status: StateFlow<RealtimeStatus> =
        combine(connectionStatus, subscriptionStatus, RealtimeStatus::worseOf)
            .stateIn(scope, SharingStarted.Eagerly, RealtimeStatus.Idle)

    /**
     * Без replay: опоздавший экран получил бы устаревшую строку поверх свежей, загруженной по REST.
     * Буфер с запасом — строки хода работы длинного ответа идут сюда же плотной пачкой.
     */
    private val _events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

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

    /** Подписка на личный канал. С ней сверяются так же, как с [client], и по той же причине. */
    @Volatile
    private var userSubscription: Subscription? = null
    private var userChannel: String? = null

    /** Сколько раз подряд подписка не поднялась — от этого растёт пауза перед повтором. */
    private var failedAttempts = 0

    @Volatile
    private var connectJob: Job? = null

    /**
     * Поднять соединение и подписку на личный канал. Идемпотентно.
     *
     * Неудача не заканчивает попытку: сеть могла отсутствовать на старте приложения, и без повтора
     * живые события не пошли бы до перезапуска.
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
                userSubscription = null
                userChannel = null
                failedAttempts = 0

                val dying = client
                // Обнулить до close: слушатель сверяется с этим полем, и события умирающего
                // клиента должны отсеяться как чужие.
                client = null
                connectionStatus.value = RealtimeStatus.Idle
                subscriptionStatus.value = RealtimeStatus.Idle
                // close, а не disconnect: disconnect рвёт только соединение, оставляя живыми
                // executor, scheduler и пул соединений — каждый разлогин протекал бы ими.
                dying?.let { runCatching { it.close(0) } }
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

        connectionStatus.value = RealtimeStatus.Connecting

        // Первый токен нужен до создания клиента: из этого же ответа берутся адрес WebSocket и имя
        // личного канала.
        val bootstrap = runCatching { repository.userChannelToken() }.getOrElse {
            warn(it) { "токен личного канала: не получен" }
            connectionStatus.value = RealtimeStatus.Disconnected
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

        userChannel = bootstrap.channel
        subscribe(created, bootstrap.channel, bootstrap.subscriptionToken, resyncOnSubscribed = false)
        created
    }

    /**
     * Заводит подписку на личный канал. Вызывать под [lock].
     *
     * Позиция и восстановление включены: после реконнекта Centrifugo досылает пропущенное за
     * последние часы сам, и перечитывать экраны после каждого разрыва не нужно — только когда
     * восстановить не вышло.
     *
     * @param token готовый токен подписки; `null` — пусть его добудет `tokenGetter` (так поднимают
     *              подписку заново: прежний токен к тому моменту обычно и есть причина падения)
     * @param resyncOnSubscribed подписка заводится взамен умершей: позиции у новой нет, и всё, что
     *                           пришло в промежутке, потеряно — экранам надо перечитать себя
     */
    private fun subscribe(client: Client, channel: String, token: String?, resyncOnSubscribed: Boolean) {
        val options = SubscriptionOptions().apply {
            setPositioned(true)
            setRecoverable(true)
            if (token != null) this.token = token
            tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent, cb: TokenCallback) {
                    provideToken(cb, "токен $channel") {
                        val issued = repository.userChannelToken()
                        // Сервер сменил имя канала на лету — подписка уже заведена на прежнее, и
                        // свежий грант к ней не подойдёт.
                        if (issued.channel != channel) warn { "$channel: токен выписан на ${issued.channel}" }
                        issued.subscriptionToken
                    }
                }
            }
        }

        val listener = object : SubscriptionEventListener() {
            private var resyncPending = resyncOnSubscribed

            override fun onPublication(sub: Subscription, event: PublicationEvent) {
                if (sub !== userSubscription) return
                dispatch(channel, event)
            }

            override fun onSubscribed(sub: Subscription, event: SubscribedEvent) {
                if (sub !== userSubscription) return
                val lost = event.wasRecovering() == true && event.recovered != true
                trace { "$channel: подписан, recovered=${event.recovered}" }
                failedAttempts = 0
                subscriptionStatus.value = RealtimeStatus.Connected
                if (lost || resyncPending) {
                    resyncPending = false
                    trace { "$channel: пропущенное не восстановлено — экраны перечитают себя" }
                    _events.tryEmit(RealtimeEvent.Resync)
                }
            }

            override fun onSubscribing(sub: Subscription, event: SubscribingEvent) {
                if (sub !== userSubscription) return
                trace { "$channel: подписывается (${event.code}) ${event.reason}" }
                subscriptionStatus.value = RealtimeStatus.Connecting
            }

            override fun onError(sub: Subscription, event: SubscriptionErrorEvent) {
                warn(event.error) { "$channel: ошибка подписки" }
            }

            /**
             * Библиотека сама повторяет только временные ошибки. На постоянной — «нет прав»,
             * негодный токен — подписка умирает молча и навсегда, а приложение при живом WebSocket
             * выглядит работающим. Канал единственный, поэтому поднимаем его заново.
             */
            override fun onUnsubscribed(sub: Subscription, event: UnsubscribedEvent) {
                if (sub !== userSubscription) return
                subscriptionStatus.value = RealtimeStatus.Disconnected
                warn { "$channel: подписка снята (${event.code}) ${event.reason}" }
                scheduleResubscribe(sub)
            }
        }

        trace { "$channel: завожу подписку" }
        val subscription = runCatching { client.newSubscription(channel, options, listener) }
            .getOrElse {
                // В реестре осталась подписка от прошлой жизни. Своей рядом не завести, а чужая
                // пишет в чужой слушатель — снимаем и заводим заново.
                client.getSubscription(channel)?.let { stale ->
                    runCatching { client.removeSubscription(stale) }
                }
                runCatching { client.newSubscription(channel, options, listener) }.getOrNull()
            }

        userSubscription = subscription
        if (subscription == null) {
            subscriptionStatus.value = RealtimeStatus.Disconnected
            warn { "$channel: подписку завести не удалось" }
            // `onUnsubscribed` уже не придёт — некому, — и без повтора приложение осталось бы глухим.
            scheduleResubscribe(null)
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
     *
     * @param dead подписка, которую заменяем; `null` — её не удалось даже завести
     */
    private fun scheduleResubscribe(dead: Subscription?) {
        val attempt = failedAttempts++

        scope.launch {
            awaitRetry(attempt)
            lock.withLock {
                // Пока ждали, приложение остановили или подписку уже заменили — чинить нечего.
                if (userSubscription !== dead) return@withLock
                val live = client ?: return@withLock
                val channel = userChannel ?: return@withLock
                dead?.let { runCatching { live.removeSubscription(it) } }
                subscribe(live, channel, token = null, resyncOnSubscribed = true)
            }
        }
    }

    /**
     * Разложить публикацию по типу. Незнакомый тип — не поломка: в канал идут и события экранов,
     * которых в приложении нет (доски, поручения), и устаревший `webchat_activity`, который сервер
     * шлёт, пока не обновились все клиенты.
     */
    private fun dispatch(channel: String, event: PublicationEvent) {
        val envelope = runCatching {
            ApiJson.decodeFromString(RealtimeEnvelope.serializer(), String(event.data, Charsets.UTF_8))
        }.getOrNull()
        val type = envelope?.type
        val payload = envelope?.payload

        val parsed: RealtimeEvent? = runCatching {
            when (type) {
                RealtimeEventType.SESSION_CREATED, RealtimeEventType.SESSION_UPDATED ->
                    RealtimeEvent.Session(
                        created = type == RealtimeEventType.SESSION_CREATED,
                        row = ApiJson.decodeFromJsonElement<WebchatSessionDto>(payload!!),
                    )
                RealtimeEventType.WEBCHAT_AGENT_UPDATED ->
                    RealtimeEvent.Contact(ApiJson.decodeFromJsonElement<WebchatContactDto>(payload!!))
                RealtimeEventType.WEBCHAT_MESSAGE ->
                    RealtimeEvent.Message(ApiJson.decodeFromJsonElement<WebchatMessagePayload>(payload!!))
                else -> {
                    trace { "$channel: пропускаю $type" }
                    return
                }
            }
        }.getOrNull()

        if (parsed == null) {
            // Свой тип, а форма разошлась — иначе событие пропадёт молча.
            warn { "$channel: публикация не разобрана — ${describe(event)}" }
            return
        }
        trace { "$channel: ${parsed.describe()}" }
        _events.tryEmit(parsed)
    }

    private fun RealtimeEvent.describe(): String = when (this) {
        is RealtimeEvent.Session -> "${if (created) "новая" else "строка"} сессии ${row.id}"
        is RealtimeEvent.Contact -> "контакт ${row.agentId}, непрочитанных ${row.unreadCount}"
        is RealtimeEvent.Message -> "${payload.sessionId}: ${payload.stream} ${payload.messageId}"
        RealtimeEvent.Resync -> "перечитать"
    }

    private fun connectionListener() = object : EventListener() {
        override fun onConnected(client: Client, event: ConnectedEvent) {
            if (!isCurrent(client)) return
            trace { "соединение установлено" }
            connectionStatus.value = RealtimeStatus.Connected
        }

        override fun onConnecting(client: Client, event: ConnectingEvent) {
            if (!isCurrent(client)) return
            trace { "подключаюсь (${event.code}) ${event.reason}" }
            connectionStatus.value = RealtimeStatus.Connecting
        }

        override fun onDisconnected(client: Client, event: DisconnectedEvent) {
            if (!isCurrent(client)) return
            warn { "соединение потеряно (${event.code}) ${event.reason}" }
            connectionStatus.value = RealtimeStatus.Disconnected
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

    private companion object {
        const val TAG = "Realtime"

        /** Первая ступень паузы перед повтором — дальше удваивается. */
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_RETRY_DELAY_MS = 60_000L

        /** Потолок удвоений: дальше пауза упирается в [MAX_RETRY_DELAY_MS]. */
        const val MAX_BACKOFF_SHIFT = 5
    }
}
