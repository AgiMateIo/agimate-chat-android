package ru.agimate.mobile.core.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** Переспрашивание транспорта, пока тому нечего дать. Один на всё приложение. */
    private var refresh: Job? = null

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
                        observed(transport.tokens())
                        scheduleRefresh()
                    } else {
                        reconcile(session, tokens)
                    }
                }
        }
    }

    /**
     * Транспорт выдал новый токен. Не повод отправить запрос, а изменение состояния — и новость
     * про один канал, поэтому запись сливается, а не подменяет снимок целиком.
     */
    fun onTransportToken(provider: String, token: String) {
        transportTokens.update { it + (provider to token) }
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
        observed(tokens)
        return reconcile(session.current, tokens, invalidBefore = observedAt)
    }

    /**
     * Что сервер знает про уведомления на это устройство — по блоку `push` своей строки из списка
     * входов. Проверка делается на устройстве: сравнить маску с живым токеном больше негде.
     */
    suspend fun health(remote: List<PushSubscriptionDto>): PushHealth {
        if (!transport.configured) return PushHealth.Unknown
        val tokens = transport.tokens()
        // Ответ транспорта — не только материал для сравнения, но и свежее состояние. Пока он
        // здесь выбрасывался, заход на экран устройств оказывался единственным способом узнать,
        // что SDK наконец выдал токены: сверка о них не спрашивала, а колбэк поднимался как
        // побочный эффект этого самого запроса.
        observed(tokens)
        return pushHealth(remote = remote, local = tokens)
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
            // Карантин ставится до отзыва, а не после: SDK объявляет новый токен колбэком прямо
            // из `deleteTokens`, и к этому моменту правило «эти значения больше не наши» должно
            // уже действовать. Отзыв не мгновенный, `getTokens()` в этом окне отдаёт всё те же
            // значения, и без карантина следующий вход зарегистрирует мёртвые токены — так и
            // вышло 20.08.2026.
            if (tokens.isNotEmpty()) {
                registrations.writeRevocation(PushRevocation(tokens = tokens, at = System.currentTimeMillis()))
            }
            transport.dropTokens(tokens)
            transportTokens.value = emptyMap()
        }
    }

    /**
     * Полный снимок транспорта — он **заменяет** словарь, а не дополняет его: канал, пропавший из
     * ответа SDK, это тоже новость, и слать в него больше нечего. Слияние держало такой канал живым
     * вечно, и подписка на него продолжала числиться нашей.
     *
     * Пустой снимок новостью не считается: сразу после входа и после ротации SDK несколько секунд
     * отдаёт пустой список, и принять его за «каналов нет» значило бы забыть рабочие токены.
     */
    private fun observed(snapshot: Map<String, String>) {
        if (snapshot.isEmpty()) return
        transportTokens.value = snapshot
    }

    /**
     * Переспросить транспорт, пока тому нечего дать.
     *
     * После входа SDK какое-то время отдаёт либо пусто, либо то, что мы сами у него отозвали.
     * Своего события «а вот теперь готово» у него нет: колбэк о новом токене поднимает сам запрос
     * токенов. Без этих повторов приложение ждёт случайного повода — и на стенде 20.08.2026
     * дождалось только захода в профиль: две с половиной минуты после входа уведомления не
     * приходили, потому что подписки не существовало.
     *
     * Отступ растёт, попыток восемь — около четырёх минут. Дальше молча прекращаем: если за это
     * время транспорт не поднялся, дело не в задержке, и следующий запуск приложения либо экран
     * устройств спросят заново.
     */
    private fun scheduleRefresh() {
        if (refresh?.isActive == true) return
        refresh = scope.launch {
            var wait = REFRESH_FIRST_DELAY_MS
            repeat(REFRESH_ATTEMPTS) {
                delay(wait)
                if (session.current == null || usable().isNotEmpty()) return@launch
                observed(transport.tokens())
                wait = (wait * 2).coerceAtMost(REFRESH_MAX_DELAY_MS)
            }
        }
    }

    /** Что из известных токенов сейчас годно к отправке: карантин отозванных учтён. */
    private fun usable(): Map<String, String> =
        pushUsable(transportTokens.value, registrations.readRevocation(), System.currentTimeMillis())

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
        // Отозванное при выходе отсеиваем до сверки. Иначе следующий вход регистрирует токен, у
        // которого уже нет владельца: сервер снесёт его по первому уведомлению, а устройство
        // останется без канала до следующей выдачи SDK.
        val desired = pushUsable(tokens, registrations.readRevocation(), now)
        if (desired.size != tokens.size) {
            trace { "часть токенов отозвана при выходе — ждём, пока SDK выдаст другие" }
            scheduleRefresh()
        }
        val due = pushDue(
            session = session,
            desired = desired,
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
            registrations.write(PushConfirmation(session = session, tokens = desired, at = now))
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
        const val REFRESH_FIRST_DELAY_MS = 2_000L
        const val REFRESH_MAX_DELAY_MS = 60_000L
        const val REFRESH_ATTEMPTS = 8
    }
}
