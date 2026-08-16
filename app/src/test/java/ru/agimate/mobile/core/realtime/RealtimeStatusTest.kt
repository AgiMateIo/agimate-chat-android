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
}
