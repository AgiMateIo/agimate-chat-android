package ru.agimate.mobile.core.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeStatusTest {

    @Test
    fun `a live socket with a dead channel subscription is not connected`() {
        // Ровно тот случай, ради которого статус складывается из двух половин: сокет цел, а чат
        // молчит, и без этого полоска показывала бы «на связи».
        assertEquals(
            RealtimeStatus.Disconnected,
            RealtimeStatus.worseOf(RealtimeStatus.Connected, RealtimeStatus.Disconnected),
        )
    }

    @Test
    fun `connected only when both halves are up`() {
        assertEquals(
            RealtimeStatus.Connected,
            RealtimeStatus.worseOf(RealtimeStatus.Connected, RealtimeStatus.Connected),
        )
        assertEquals(
            RealtimeStatus.Connecting,
            RealtimeStatus.worseOf(RealtimeStatus.Connected, RealtimeStatus.Connecting),
        )
    }

    @Test
    fun `nothing started yet stays idle`() {
        assertEquals(
            RealtimeStatus.Idle,
            RealtimeStatus.worseOf(RealtimeStatus.Idle, RealtimeStatus.Idle),
        )
    }

    /**
     * Регрессия. Подписка на канал отчитывается позже соединения, и пока она молчит, вторая
     * половина — `Idle`. Считать это состояние «ничего не начиналось» нельзя: экран заводит по нему
     * таймер и показывал бы «связь потеряна» поверх исправного сокета.
     */
    @Test
    fun `one half still unknown reads as connecting, not idle`() {
        assertEquals(
            RealtimeStatus.Connecting,
            RealtimeStatus.worseOf(RealtimeStatus.Connected, RealtimeStatus.Idle),
        )
        assertEquals(
            RealtimeStatus.Connecting,
            RealtimeStatus.worseOf(RealtimeStatus.Idle, RealtimeStatus.Connected),
        )
    }
}
