package ru.agimate.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Test

/** Правило сверки: что из желаемого надо отправить серверу. */
class PushDueTest {

    private val now = 1_000_000L
    private val desired = mapOf("rustore" to "T1")

    @Test
    fun `подтверждали то же самое — молчим`() {
        val confirmed = PushConfirmation(session = "s1", tokens = desired, at = now)

        assertEquals(emptyMap<String, String>(), pushDue("s1", desired, confirmed, now))
    }

    @Test
    fun `перевход с тем же токеном — регистрируем заново`() {
        // Подписка принадлежит входу: со старой сессией сервер её уже удалил.
        val confirmed = PushConfirmation(session = "s1", tokens = desired, at = now)

        assertEquals(desired, pushDue("s2", desired, confirmed, now))
    }

    @Test
    fun `токен сменился — отправляем только его`() {
        val confirmed = PushConfirmation(
            session = "s1",
            tokens = mapOf("rustore" to "T1", "firebase" to "F1"),
            at = now,
        )
        val rotated = mapOf("rustore" to "T2", "firebase" to "F1")

        assertEquals(mapOf("rustore" to "T2"), pushDue("s1", rotated, confirmed, now))
    }

    @Test
    fun `подтверждали больше суток назад — продлеваем`() {
        val confirmed = PushConfirmation(session = "s1", tokens = desired, at = now)

        assertEquals(desired, pushDue("s1", desired, confirmed, now + PUSH_CONFIRM_INTERVAL_MS))
    }

    @Test
    fun `не подтверждали вовсе`() {
        assertEquals(desired, pushDue("s1", desired, confirmed = null, now = now))
    }

    @Test
    fun `починка отменяет подтверждение, сделанное до наблюдения`() {
        val confirmed = PushConfirmation(session = "s1", tokens = desired, at = now)

        assertEquals(desired, pushDue("s1", desired, confirmed, now + 1, invalidBefore = now))
    }

    @Test
    fun `починка не трогает подтверждение, пришедшее после наблюдения`() {
        // Сверка успела отправить, пока читался список устройств: сервер этого ещё не видел,
        // и переотправлять тот же токен незачем — ровно с этого начинался дубль.
        val confirmed = PushConfirmation(session = "s1", tokens = desired, at = now)

        assertEquals(
            emptyMap<String, String>(),
            pushDue("s1", desired, confirmed, now, invalidBefore = now - 1_000),
        )
    }

    @Test
    fun `токенов нет — отправлять нечего`() {
        assertEquals(
            emptyMap<String, String>(),
            pushDue("s1", desired = emptyMap(), confirmed = null, now = now, invalidBefore = now),
        )
    }
}
