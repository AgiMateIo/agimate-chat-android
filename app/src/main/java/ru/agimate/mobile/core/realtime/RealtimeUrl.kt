package ru.agimate.mobile.core.realtime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.network.OriginProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адрес WebSocket берётся из ответа сервера — зашивать его нельзя.
 *
 * Единственное исключение — **локальный стенд**. Там сервер отдаёт адрес вида
 * `ws://centrifugo.agimate.lc:8000/connection/websocket`: этот хост живёт в /etc/hosts машины
 * разработчика, и ни эмулятор, ни телефон его не резолвят. Caddy при этом маршрутизирует
 * `/connection/websocket` с любого хоста, поэтому адрес переписывается на тот origin, по которому
 * приложение уже успешно ходит по HTTP.
 *
 * У настоящего сервера Centrifugo стоит на своём хосте (`centrifugo.…`), а `/connection/websocket`
 * на хосте API отдаёт 404. Переписать адрес там значит увести WebSocket в никуда — и это не видно:
 * библиотека повторяет подключение молча и бесконечно, HTTP при этом работает, а живые сообщения
 * просто не приходят.
 *
 * Поэтому решает не флаг сборки, а адреса, и локальными должны быть **оба**: и origin, по которому
 * приложение ходит, и хост, который назвал сервер. Хост из ответа — прямой признак: именно его
 * устройство не может разрезолвить. Origin — подтверждение, что подменять есть на что.
 * Флаг сборки остался внешней страховкой: в релизе origin и так прибит к `BuildConfig.API_ORIGIN`.
 */
@Singleton
class RealtimeUrl @Inject constructor(
    private val origins: OriginProvider,
) {
    fun resolve(serverWsUrl: String): String {
        if (!BuildConfig.ALLOW_ORIGIN_OVERRIDE) return serverWsUrl
        return resolveAgainst(serverWsUrl, origins.current)
    }
}

/**
 * Чистая половина [RealtimeUrl.resolve]: что получится, если приложение ходит по [origin].
 *
 * Вынесено из класса, чтобы правило проверялось тестом без DI и BuildConfig.
 */
internal fun resolveAgainst(serverWsUrl: String, origin: String): String {
    val parsed = origin.toHttpUrlOrNull() ?: return serverWsUrl
    if (!isLocalStand(parsed.host)) return serverWsUrl
    if (!isLocalStand(serverWsUrl.substringAfter("://", "").substringBefore('/').substringBefore(':'))) {
        return serverWsUrl
    }

    val path = serverWsUrl.substringAfter("://", "").substringAfter('/', "connection/websocket")
    val scheme = if (parsed.isHttps) "wss" else "ws"
    val port = if (parsed.port == okhttp3.HttpUrl.defaultPort(parsed.scheme)) {
        ""
    } else {
        ":${parsed.port}"
    }
    return "$scheme://${parsed.host}$port/${path.trimStart('/')}"
}

/**
 * Локальный стенд: петля, адрес хост-машины из эмулятора, LAN или служебное имя разработчика.
 * Всё остальное считается настоящим сервером, и его адрес трогать нельзя.
 */
internal fun isLocalStand(host: String): Boolean {
    if (host.isEmpty()) return false
    // Имя без точек — это имя из локальной сети или /etc/hosts, в публичном DNS такого нет.
    if (!host.contains('.')) return true
    if (host.endsWith(".lc", ignoreCase = true) || host.endsWith(".local", ignoreCase = true)) {
        return true
    }

    val octets = host.split('.')
    if (octets.size != 4) return false
    val numbers = octets.map { it.toIntOrNull() ?: return false }
    if (numbers.any { it !in 0..255 }) return false

    // Приватные диапазоны IPv4. `10.0.2.2` — хост-машина со стороны эмулятора — попадает в первый.
    return when {
        numbers[0] == 127 -> true
        numbers[0] == 10 -> true
        numbers[0] == 192 && numbers[1] == 168 -> true
        numbers[0] == 172 && numbers[1] in 16..31 -> true
        // Link-local и CGNAT: сюда попадают адреса Tailscale и раздача без DHCP.
        numbers[0] == 169 && numbers[1] == 254 -> true
        numbers[0] == 100 && numbers[1] in 64..127 -> true
        else -> false
    }
}
