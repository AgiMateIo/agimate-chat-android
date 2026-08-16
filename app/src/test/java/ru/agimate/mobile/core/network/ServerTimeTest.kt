package ru.agimate.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Бэкенд отдаёт время четырьмя разными способами, и все четыре встречаются в одном экране.
 * Один толерантный парсер — единственное место, где это лечится.
 */
class ServerTimeTest {

    @Test
    fun `iso local date time without zone is read as UTC`() {
        assertEquals(
            Instant.parse("2026-08-15T16:44:13.310006Z"),
            ServerTime.parse("2026-08-15T16:44:13.310006"),
        )
    }

    @Test
    fun `iso without fraction is accepted`() {
        assertEquals(
            Instant.parse("2026-08-15T16:44:13Z"),
            ServerTime.parse("2026-08-15T16:44:13"),
        )
    }

    @Test
    fun `centrifugo publishes instants with a trailing Z`() {
        assertEquals(
            Instant.parse("2026-08-15T16:43:13.310Z"),
            ServerTime.parse("2026-08-15T16:43:13.310Z"),
        )
    }

    @Test
    fun `profile uses a space instead of T`() {
        assertEquals(
            Instant.parse("2026-08-15T21:05:21Z"),
            ServerTime.parse("2026-08-15 21:05:21"),
        )
    }

    @Test
    fun `offset is honoured when present`() {
        assertEquals(
            Instant.parse("2026-08-15T13:44:13Z"),
            ServerTime.parse("2026-08-15T16:44:13+03:00"),
        )
    }
}
