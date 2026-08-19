package ru.agimate.mobile.core.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationLogTest {

    private val day = PUSH_CONFIRM_INTERVAL_MS

    @Test
    fun `свежая отметка не заставляет подтверждать`() {
        assertFalse(pushConfirmationStale(last = 1_000L, now = 1_000L + day / 2))
    }

    @Test
    fun `сутки без подтверждения — пора`() {
        assertTrue(pushConfirmationStale(last = 1_000L, now = 1_000L + day))
    }

    @Test
    fun `отметки не было вовсе`() {
        assertTrue(pushConfirmationStale(last = 0L, now = 1_000L))
    }

    @Test
    fun `часы перевели назад — подтверждаем, а не ждём`() {
        assertTrue(pushConfirmationStale(last = 10_000L, now = 5_000L))
    }
}
