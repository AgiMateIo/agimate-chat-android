package ru.agimate.mobile.core.realtime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.network.OriginProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адрес WebSocket берётся из ответа сервера — зашивать его нельзя.
 *
 * Исключение — отладочная сборка. Там сервер отдаёт адрес вида
 * `ws://centrifugo.agimate.lc:8000/connection/websocket`: этот хост живёт в /etc/hosts машины
 * разработчика, и ни эмулятор, ни телефон его не резолвят. Caddy при этом маршрутизирует
 * `/connection/websocket` с любого хоста, поэтому в dev адрес переписывается на тот origin, по
 * которому приложение уже успешно ходит по HTTP. В prod ответ используется как есть.
 */
@Singleton
class RealtimeUrl @Inject constructor(
    private val origins: OriginProvider,
) {
    fun resolve(serverWsUrl: String): String {
        if (!BuildConfig.ALLOW_ORIGIN_OVERRIDE) return serverWsUrl

        val origin = origins.current.toHttpUrlOrNull() ?: return serverWsUrl
        val path = serverWsUrl.substringAfter("://", "").substringAfter('/', DEFAULT_PATH)
        val scheme = if (origin.isHttps) "wss" else "ws"
        val port = if (origin.port == okhttp3.HttpUrl.defaultPort(origin.scheme)) {
            ""
        } else {
            ":${origin.port}"
        }
        return "$scheme://${origin.host}$port/${path.trimStart('/')}"
    }

    private companion object {
        const val DEFAULT_PATH = "connection/websocket"
    }
}
