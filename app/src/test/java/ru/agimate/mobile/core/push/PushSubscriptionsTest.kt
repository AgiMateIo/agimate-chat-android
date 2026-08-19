package ru.agimate.mobile.core.push

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.core.auth.AuthTokens
import ru.agimate.mobile.core.auth.CurrentSession
import ru.agimate.mobile.core.auth.FakeTokenStore
import ru.agimate.mobile.core.network.ApiEnvelope
import ru.agimate.mobile.data.push.PushApi
import ru.agimate.mobile.data.push.PushSubscriptionRequest
import ru.agimate.mobile.data.push.PushUnsubscribeRequest

private const val PROVIDER = "rustore"

/**
 * Ради этого класс и появился: у SDK запрос токенов сам поднимает колбэк «новый токен», и пока
 * отправителей было двое — ветка входа и ветка колбэка, — один и тот же токен уезжал на сервер
 * дважды. Поэтому фейковый транспорт здесь ведёт себя так же.
 *
 * Сведение живёт в `backgroundScope`, а его с coroutines 1.10 не трогает `advanceUntilIdle()` —
 * отсюда `runCurrent()`.
 */
class PushSubscriptionsTest {

    @Test
    fun `вход и колбэк транспорта дают одну регистрацию`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val transport = FakeTransport(token = "T1")
        val subscriptions = subscriptions(transport, api, store)

        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        assertEquals(listOf("T1"), api.subscribed.map { it.token })
        assertEquals(listOf(PROVIDER), api.subscribed.map { it.provider })
    }

    @Test
    fun `тот же токен ещё раз — сервер не трогаем`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val subscriptions = subscriptions(FakeTransport(token = "T1"), api, store)
        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        subscriptions.onTransportToken(PROVIDER, "T1")
        runCurrent()

        assertEquals(1, api.subscribed.size)
    }

    @Test
    fun `перевход регистрирует заново — подписка принадлежит входу`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val transport = FakeTransport(token = "T1", afterDrop = "T2")
        subscriptions(transport, api, store)
        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        // Вход кончился не по нашей воле: токены вычищены, транспорт отозван, SDK выдал новый.
        store.clear()
        runCurrent()
        store.save(AuthTokens("access", "refresh", "s2"))
        runCurrent()

        assertEquals(listOf("T1", "T2"), api.subscribed.map { it.token })
        assertEquals(listOf(mapOf(PROVIDER to "T1")), transport.dropped)
    }

    @Test
    fun `выход не регистрирует токен, выданный взамен отозванного`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val subscriptions = subscriptions(FakeTransport(token = "T1", afterDrop = "T2"), api, store)
        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        // Снятие подписки идёт до очистки токенов, то есть вход в этот момент ещё жив.
        subscriptions.signOut()
        runCurrent()
        store.clear()
        runCurrent()

        assertEquals(listOf("T1"), api.unsubscribed.map { it.token })
        assertEquals(listOf("T1"), api.subscribed.map { it.token })
    }

    @Test
    fun `починка отправляет заново, если сервер видел старое состояние`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val subscriptions = subscriptions(FakeTransport(token = "T1"), api, store)
        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        // Список устройств прочитан позже нашего подтверждения — значит сервер его уже опроверг.
        assertTrue(subscriptions.repair(observedAt = System.currentTimeMillis() + 1_000))

        assertEquals(listOf("T1", "T1"), api.subscribed.map { it.token })
    }

    @Test
    fun `починка не задваивает то, что сверка отправила после наблюдения`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val observedAt = System.currentTimeMillis() - 1_000
        val subscriptions = subscriptions(FakeTransport(token = "T1"), api, store)

        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        assertFalse(subscriptions.repair(observedAt = observedAt))

        assertEquals(listOf("T1"), api.subscribed.map { it.token })
    }

    @Test
    fun `выход снимает подписку и отзывает токен`() = runTest {
        val store = FakeTokenStore(null)
        val api = RecordingPushApi()
        val transport = FakeTransport(token = "T1")
        val subscriptions = subscriptions(transport, api, store)
        store.save(AuthTokens("access", "refresh", "s1"))
        runCurrent()

        subscriptions.signOut()

        assertEquals(listOf("T1"), api.unsubscribed.map { it.token })
        assertEquals(listOf(mapOf(PROVIDER to "T1")), transport.dropped)
    }

    /** Собранный так же, как в приложении: колбэк транспорта уходит владельцу подписки. */
    private fun kotlinx.coroutines.test.TestScope.subscriptions(
        transport: FakeTransport,
        api: PushApi,
        store: FakeTokenStore,
    ): PushSubscriptions {
        val subscriptions = PushSubscriptions(
            transport = transport,
            api = api,
            registrations = InMemoryRegistrationLog(),
            session = CurrentSession(store, backgroundScope),
            scope = backgroundScope,
        )
        transport.onToken = subscriptions::onTransportToken
        subscriptions.start()
        return subscriptions
    }
}

/** Транспорт, который ведёт себя как SDK: запрос токенов сам поднимает колбэк «новый токен». */
private class FakeTransport(
    private var token: String?,
    private val afterDrop: String? = null,
) : PushTransport {

    var onToken: ((String, String) -> Unit)? = null
    val dropped = mutableListOf<Map<String, String>>()

    override val configured: Boolean = true

    override fun start(
        onMessage: (PushMessage) -> Unit,
        onToken: (provider: String, token: String) -> Unit,
    ) {
        this.onToken = onToken
    }

    override suspend fun tokens(): Map<String, String> {
        val current = token ?: return emptyMap()
        onToken?.invoke(PROVIDER, current)
        return mapOf(PROVIDER to current)
    }

    override suspend fun dropTokens(tokens: Map<String, String>) {
        if (tokens.isEmpty()) return
        dropped += tokens
        token = afterDrop
        // Как настоящий SDK: отозвали токен — он тут же выдаёт новый и объявляет его колбэком.
        afterDrop?.let { onToken?.invoke(PROVIDER, it) }
    }
}

private class RecordingPushApi : PushApi {

    val subscribed = mutableListOf<PushSubscriptionRequest>()
    val unsubscribed = mutableListOf<PushUnsubscribeRequest>()

    override suspend fun subscribe(body: PushSubscriptionRequest): ApiEnvelope<String> {
        subscribed += body
        return ApiEnvelope("ok")
    }

    override suspend fun unsubscribe(body: PushUnsubscribeRequest): ApiEnvelope<String> {
        unsubscribed += body
        return ApiEnvelope("ok")
    }
}

private class InMemoryRegistrationLog : PushRegistrationLog {

    private var confirmation: PushConfirmation? = null

    override fun read(): PushConfirmation? = confirmation

    override fun write(confirmation: PushConfirmation) {
        this.confirmation = confirmation
    }

    override fun forget() {
        confirmation = null
    }
}
