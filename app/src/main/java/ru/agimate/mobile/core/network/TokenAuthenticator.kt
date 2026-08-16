package ru.agimate.mobile.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import ru.agimate.mobile.core.auth.TokenRefresher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 401 → обновиться и повторить запрос. Само обновление однопоточное и живёт в [TokenRefresher];
 * здесь только точка входа со стороны OkHttp.
 *
 * `runBlocking` тут уместен: OkHttp зовёт аутентификатор со своего рабочего потока и ждёт ответа
 * синхронно, а параллельные 401 всё равно упрутся в мьютекс внутри рефрешера.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val refresher: TokenRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Один повтор на запрос. Если и с новым токеном 401 — обновление не помогает, и второй
        // круг только утроит трафик.
        if (response.priorResponse != null) return null

        val stale = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()

        return when (val outcome = runBlocking { refresher.refresh(stale) }) {
            is TokenRefresher.Outcome.Refreshed ->
                response.request.newBuilder()
                    .header("Authorization", "Bearer ${outcome.accessToken}")
                    .build()

            // Сессия мертва, связи нет или входа не было — повторять нечего. Токены уже вычищены
            // рефрешером там, где это нужно; приложение узнает об этом через TokenStore.tokens.
            TokenRefresher.Outcome.SessionDead,
            TokenRefresher.Outcome.Offline,
            TokenRefresher.Outcome.NotSignedIn -> null
        }
    }
}
