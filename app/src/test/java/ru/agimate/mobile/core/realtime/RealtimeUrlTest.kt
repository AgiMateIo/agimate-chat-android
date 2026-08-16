package ru.agimate.mobile.core.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeUrlTest {

    /** Ради чего подмена вообще существует: хост стенда живёт в /etc/hosts и с телефона не резолвится. */
    @Test
    fun `local stand address is rewritten onto the origin the app already talks to`() {
        assertEquals(
            "ws://10.0.2.2:8000/connection/websocket",
            resolveAgainst("ws://centrifugo.agimate.lc:8000/connection/websocket", "http://10.0.2.2:8000"),
        )
    }

    @Test
    fun `a LAN address counts as the local stand too`() {
        assertEquals(
            "ws://192.168.1.42:8000/connection/websocket",
            resolveAgainst("ws://centrifugo.agimate.lc:8000/connection/websocket", "http://192.168.1.42:8000"),
        )
    }

    /**
     * Регрессия. У настоящего сервера Centrifugo стоит на своём хосте, а `/connection/websocket` на
     * хосте API отдаёт 404 — и подмена уводила WebSocket в никуда молча: HTTP работал, чат был
     * открыт, живые сообщения не приходили.
     */
    @Test
    fun `a real server address is left alone`() {
        val server = "wss://centrifugo.agimate.ru/connection/websocket"

        assertEquals(server, resolveAgainst(server, "https://api.agimate.ru"))
    }

    /** Локальным должен быть и хост из ответа: стенд в LAN может назвать достижимый публичный адрес. */
    @Test
    fun `a reachable address from a local stand is left alone`() {
        val server = "wss://centrifugo.example.com/connection/websocket"

        assertEquals(server, resolveAgainst(server, "http://192.168.1.42:8000"))
    }

    @Test
    fun `an unparsable origin changes nothing`() {
        val server = "wss://centrifugo.agimate.ru/connection/websocket"

        assertEquals(server, resolveAgainst(server, "не адрес"))
    }

    @Test
    fun `local stand hosts are recognised`() {
        listOf(
            "localhost", "mymachine", "127.0.0.1", "10.0.2.2", "192.168.0.10", "172.20.1.1",
            "centrifugo.agimate.lc", "stand.local",
            // Link-local и CGNAT: раздача без DHCP и адреса Tailscale.
            "169.254.10.1", "100.100.0.5",
        ).forEach { assertTrue(it, isLocalStand(it)) }
    }

    @Test
    fun `public hosts are not local`() {
        listOf(
            "api.agimate.ru", "www.agimate.io", "centrifugo.agimate.ru",
            "172.32.0.1", "11.0.0.1", "100.128.0.1", "",
        ).forEach { assertFalse(it, isLocalStand(it)) }
    }
}
