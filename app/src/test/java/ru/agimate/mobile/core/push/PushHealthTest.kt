package ru.agimate.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.data.user.PushSubscriptionDto

/**
 * Сверка идёт по началу токена: целиком сервер его не отдаёт никому. Ошибка разбора маски стоит
 * дорого в обе стороны — либо приложение молча остаётся без уведомлений, либо перерегистрируется
 * при каждом открытии экрана.
 */
class PushHealthTest {

    private val token = "cV8kQz1pXXXXXXXXXXXXXXXX"
    private val local = mapOf("rustore" to token)

    private fun remote(vararg masked: String) =
        masked.map { PushSubscriptionDto(provider = "RUSTORE", maskedToken = it) }

    @Test
    fun `token from the sdk matches its mask on the server`() {
        assertEquals(PushHealth.Working, pushHealth(remote("cV8kQz1p…"), local))
    }

    @Test
    fun `no subscription at all means the server has nowhere to send`() {
        assertEquals(PushHealth.Missing, pushHealth(emptyList(), local))
    }

    @Test
    fun `another token on the server means ours is stale`() {
        assertEquals(PushHealth.Stale, pushHealth(remote("ZZZZZZZZ…"), local))
    }

    /** Две-три записи — норма: старая живёт, пока транспорт не откажет. */
    @Test
    fun `an extra record next to ours is not a failure`() {
        assertEquals(PushHealth.Working, pushHealth(remote("ZZZZZZZZ…", "cV8kQz1p…"), local))
    }

    @Test
    fun `three dots instead of an ellipsis are understood too`() {
        assertEquals(PushHealth.Working, pushHealth(remote("cV8kQz1p..."), local))
    }

    @Test
    fun `an empty mask proves nothing`() {
        assertEquals(PushHealth.Stale, pushHealth(remote("…"), local))
    }

    @Test
    fun `a mask of another transport does not count`() {
        val alien = listOf(PushSubscriptionDto(provider = "FIREBASE", maskedToken = "cV8kQz1p…"))
        assertEquals(PushHealth.Stale, pushHealth(alien, local))
    }

    /** Токена ещё нет — настройка не закончена, и «уведомления не приходят» здесь было бы враньём. */
    @Test
    fun `without a token from the sdk there is nothing to compare`() {
        assertEquals(PushHealth.Preparing, pushHealth(remote("cV8kQz1p…"), emptyMap()))
    }

    /**
     * Два канала: RuStore переподписывается и на это время пропадает с сервера, FCM доставляет.
     * Инцидент 20.08.2026 — экран говорил «уведомления не приходят» ровно в тот момент, когда
     * уведомление пришло.
     */
    @Test
    fun `one channel of two still delivers`() {
        val both = mapOf("rustore" to token, "firebase" to "dD4u9DhOYYYYYYYY")
        val onlyFirebase = listOf(PushSubscriptionDto(provider = "FIREBASE", maskedToken = "dD4u9DhO…"))

        val health = pushHealth(onlyFirebase, both)

        assertEquals(PushHealth.Partial, health)
        assertTrue("уведомления приходят, пока жив хоть один канал", health.delivering)
        assertTrue("недостающий канал всё равно надо зарегистрировать", health.fixable)
    }

    /** Ни один канал не найден — это уже не «часть», а устаревшая подписка. */
    @Test
    fun `no channel of two is stale, not partial`() {
        val both = mapOf("rustore" to token, "firebase" to "dD4u9DhOYYYYYYYY")

        assertEquals(PushHealth.Stale, pushHealth(remote("ZZZZZZZZ…"), both))
    }

    @Test
    fun `both failures are cured the same way`() {
        assertEquals(true, PushHealth.Missing.fixable)
        assertEquals(true, PushHealth.Stale.fixable)
        assertEquals(false, PushHealth.Working.fixable)
        assertEquals(false, PushHealth.Unknown.fixable)
        // Перерегистрация без токена отправила бы пустоту: ждём, пока транспорт его выдаст.
        assertEquals(false, PushHealth.Preparing.fixable)
    }
}
