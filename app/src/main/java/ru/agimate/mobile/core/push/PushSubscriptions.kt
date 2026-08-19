package ru.agimate.mobile.core.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.auth.CurrentSession
import ru.agimate.mobile.core.di.ApplicationScope
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.data.push.PushApi
import ru.agimate.mobile.data.push.PushSubscriptionRequest
import ru.agimate.mobile.data.push.PushUnsubscribeRequest
import ru.agimate.mobile.data.user.PushSubscriptionDto
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Что сервер знает про уведомления на это устройство — и единственный, кто это меняет.
 *
 * Желаемое состояние складывается из двух вещей: какой сейчас вход и какие токены держит транспорт.
 * Обе приходят потоками, сводит их один коллектор, а отправляет [reconcile] — больше `subscribe`
 * не зовёт никто. Это и есть смысл класса: пока отправителей было трое (вход, новый токен от SDK,
 * починка с экрана устройств), один и тот же токен уезжал на сервер по два раза.
 *
 * Тот же приём уже сделан у real-time: соединение и подписки на каналы держит один владелец, и
 * сколько бы экранов ни позвало `start()`, соединение остаётся одно.
 */
@Singleton
class PushSubscriptions @Inject constructor(
    private val transport: PushTransport,
    private val api: PushApi,
    private val registrations: PushRegistrationLog,
    private val session: CurrentSession,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Токены транспорта как состояние, а не как событие.
     *
     * Здесь и лечится исходная гонка: `tokens()` и колбэк SDK о новом токене приносят **одно и то
     * же значение в одно поле**, а не два независимых повода сходить на сервер.
     */
    private val transportTokens = MutableStateFlow<Map<String, String>>(emptyMap())

    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    /**
     * Вход, с которого подписку уже сняли.
     *
     * Выход — не мгновение, а окно: снятие подписки идёт до очистки токенов, а отзыв токена у
     * транспорта заставляет SDK тут же выдать новый и объявить его колбэком. Без этой отметки вход
     * ещё жив, состояние изменилось — и сверка добросовестно регистрирует свежий токен сразу после
     * `DELETE`, возвращая в список устройство, которое только что вышло.
     *
     * Читается и пишется под [mutex].
     */
    private var retired: String? = null

    /** Зовётся из `Application.onCreate` вместе с подъёмом транспорта. */
    fun start() {
        if (!transport.configured || !started.compareAndSet(false, true)) return

        scope.launch {
            combine(session.id, transportTokens) { session, tokens -> session to tokens }
                .distinctUntilChanged()
                .collect { (session, tokens) ->
                    // Вход есть, а токенов у нас нет — спросить транспорт самим: после выхода они
                    // отозваны, и принести их больше некому. Ответ придёт сюда же следующим
                    // состоянием, поэтому сверку на этом заходе делать не о чем.
                    if (session != null && tokens.isEmpty()) {
                        remember(transport.tokens())
                    } else {
                        reconcile(session, tokens)
                    }
                }
        }
    }

    /** Транспорт выдал новый токен. Не повод отправить запрос, а изменение состояния. */
    fun onTransportToken(provider: String, token: String) {
        remember(mapOf(provider to token))
    }

    /**
     * Расхождение с сервером, замеченное на экране устройств: и «подписки нет», и «на сервере
     * устаревший токен» лечатся одинаково — регистрацией заново.
     *
     * @param observedAt когда сервера об этом спросили. Опровергнуты только подтверждения не позже
     *   этой отметки; всё, что подтвердилось после, сервер ещё не видел — и переотправлять это
     *   значит слать тот же токен дважды.
     * @return был ли отправлен хоть один токен: список устройств после этого стоит перечитать.
     */
    suspend fun repair(observedAt: Long): Boolean {
        if (!transport.configured) return false
        val tokens = transport.tokens()
        remember(tokens)
        return reconcile(session.current, tokens, invalidBefore = observedAt)
    }

    /**
     * Что сервер знает про уведомления на это устройство — по блоку `push` своей строки из списка
     * входов. Проверка делается на устройстве: сравнить маску с живым токеном больше негде.
     */
    suspend fun health(remote: List<PushSubscriptionDto>): PushHealth {
        if (!transport.configured) return PushHealth.Unknown
        return pushHealth(remote = remote, local = transport.tokens())
    }

    /**
     * Выход из аккаунта. Зовётся из контура сессии **до** очистки токенов доступа: после неё запрос
     * уйдёт без авторизации, и устройство осталось бы в списке — с уведомлениями о переписках,
     * которых человек больше не видит.
     */
    suspend fun signOut() {
        if (!transport.configured) return
        mutex.withLock {
            retired = session.current
            registrations.forget()
            val tokens = transport.tokens()
            tokens.forEach { (provider, token) ->
                runCatching { apiCall { api.unsubscribe(PushUnsubscribeRequest(token = token)) } }
                    .onFailure { error -> warn(error) { "не удалось снять подписку $provider" } }
            }
            transport.dropTokens(tokens)
            transportTokens.value = emptyMap()
        }
    }

    private fun remember(tokens: Map<String, String>) {
        if (tokens.isEmpty()) return
        transportTokens.update { it + tokens }
    }

    /**
     * Свести серверное состояние к желаемому. Единственное место, откуда уходит регистрация.
     *
     * Под мьютексом, потому что заходов два: коллектор состояния и починка с экрана устройств. Без
     * него оба прочитали бы одну и ту же памятку и всё равно отправили бы по разу.
     */
    private suspend fun reconcile(
        session: String?,
        tokens: Map<String, String>,
        invalidBefore: Long = 0L,
    ): Boolean = mutex.withLock {
        if (session == null) {
            retired = null
            // Вход кончился не по нашей воле — истёк или отозван токен обновления. Серверную запись
            // в этом случае чистит сам сервер; здесь важно, чтобы транспорт перестал доставлять.
            // Ровно один раз, по наличию памятки: отзыв токена заставляет SDK выдать новый, и без
            // этого условия отзыв и выдача гоняли бы друг друга по кругу.
            if (registrations.read() != null) {
                registrations.forget()
                transport.dropTokens(transport.tokens())
                // Отозванные токены больше не описывают транспорт: следующий вход спросит заново.
                transportTokens.value = emptyMap()
            }
            return@withLock false
        }

        // Этот вход уже снят с подписки и доживает последние миллисекунды: регистрировать нечего,
        // даже по требованию — экрана, с которого чинят, в этот момент уже нет.
        if (session == retired) return@withLock false

        val now = System.currentTimeMillis()
        val due = pushDue(
            session = session,
            desired = tokens,
            confirmed = registrations.read(),
            now = now,
            invalidBefore = invalidBefore,
        )
        if (due.isEmpty()) return@withLock false

        val delivered = due.filter { (provider, token) -> send(provider, token) }
        if (delivered.isEmpty()) return@withLock false
        // Памятка двигается, только когда отправлено всё, что было должно: иначе недоехавший токен
        // считался бы подтверждённым до следующих суток, а он на сервере так и не появился.
        if (delivered.size == due.size) {
            registrations.write(PushConfirmation(session = session, tokens = tokens, at = now))
        }
        true
    }

    /**
     * Неудача проглатывается: без уведомлений приложение остаётся рабочим, а показывать человеку
     * ошибку, о которой он не просил, незачем. Следующая сверка отправит снова.
     */
    private suspend fun send(provider: String, token: String): Boolean =
        runCatching { apiCall { api.subscribe(PushSubscriptionRequest(provider = provider, token = token)) } }
            .onSuccess { trace { "подписка $provider зарегистрирована" } }
            .onFailure { error -> warn(error) { "не удалось зарегистрировать подписку $provider" } }
            .isSuccess

    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message())
    }

    private inline fun warn(error: Throwable? = null, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val text = message()
        if (error != null) Log.w(TAG, text, error) else Log.w(TAG, text)
    }

    private companion object {
        const val TAG = "AgiPush"
    }
}
