package ru.agimate.mobile.core.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Обновление токенов — однопоточное (single-flight).
 *
 * На холодном старте приложение легко выпускает пять параллельных запросов; все получают 401 и все
 * идут обновляться. Ротация — условная запись: поколение сдвинет ровно один, остальные получат
 * `409`. Поэтому обновление под мьютексом: один запрос обновляется, остальные ждут его результата и
 * повторяются с новым токеном.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val store: TokenStore,
    private val api: Provider<AuthApi>,
    private val clock: Clock = Clock.System,
) {
    fun interface Clock {
        fun nowMillis(): Long

        companion object {
            val System = Clock { java.lang.System.currentTimeMillis() }
        }
    }

    sealed interface Outcome {
        /** Есть свежий access — исходный запрос можно повторить. */
        data class Refreshed(val accessToken: String) : Outcome

        /** 403/401: сессия отозвана, истекла или токен предъявлен после ротации. Вход заново. */
        data object SessionDead : Outcome

        /** Связи нет. Токены целы, разлогинивать нельзя. */
        data object Offline : Outcome

        /** Токенов в хранилище нет — обновляться нечем. */
        data object NotSignedIn : Outcome
    }

    private val mutex = Mutex()

    /**
     * @param staleAccessToken токен, с которым запрос получил 401. Если пока ждали мьютекс, его уже
     *                         кто-то обновил, повторять обновление не нужно — отдаём актуальный.
     */
    suspend fun refresh(staleAccessToken: String?): Outcome = mutex.withLock {
        val current = store.load() ?: return Outcome.NotSignedIn
        if (staleAccessToken != null && staleAccessToken != current.accessToken) {
            return Outcome.Refreshed(current.accessToken)
        }
        perform(current)
    }

    /**
     * Обновиться заранее, не дожидаясь 401. Зовётся при возвращении в приложение: часовой токен
     * успевает протухнуть, пока телефон спит, и первый же запрос после сна иначе платит лишним
     * кругом «401 — обновление — повтор».
     *
     * Ничего не делает, пока до конца срока больше десятой части: обновление ротирует пару, и
     * будить его чаще, чем нужно, значит без повода менять то, что работает.
     */
    suspend fun refreshIfDue(): Outcome = mutex.withLock {
        val current = store.load() ?: return Outcome.NotSignedIn
        if (!current.renewalDue(clock.nowMillis())) return Outcome.Refreshed(current.accessToken)
        perform(current)
    }

    private suspend fun perform(tokens: AuthTokens): Outcome {
        val startedAt = clock.nowMillis()
        var attempt = 0

        while (true) {
            attempt++
            val response = try {
                api.get().refresh(RefreshRequest(tokens.refreshToken))
            } catch (e: IOException) {
                // Ответ мог потеряться по дороге. Сервер это знает: обновление, ответ на которое не
                // доехал, можно повторить ТЕМ ЖЕ токеном в течение минуты после ротации — вернётся
                // актуальная пара. Начинать вход заново здесь нельзя.
                if (!withinRetryWindow(startedAt)) return Outcome.Offline
                delay(backoffMillis(attempt))
                continue
            }

            when (val code = response.code()) {
                200 -> {
                    val dto = response.body()?.response
                        ?: return Outcome.SessionDead
                    val fresh = dto.toTokens(clock.nowMillis())
                    store.save(fresh)
                    return Outcome.Refreshed(fresh.accessToken)
                }

                409 -> {
                    // Параллельное обновление, победил другой. Разлогинивать нельзя: подождать свой
                    // single-flight и повторить с актуальным токеном.
                    val latest = store.load() ?: return Outcome.NotSignedIn
                    if (latest.refreshToken != tokens.refreshToken) {
                        return Outcome.Refreshed(latest.accessToken)
                    }
                    if (!withinRetryWindow(startedAt)) return Outcome.Offline
                    delay(backoffMillis(attempt))
                }

                // 403 — всегда «вход заново»: сессию отозвали с другого устройства, токен предъявлен
                // после ротации или сессия истекла. Причины разные, действие одно.
                // 401 — токена в запросе не было: обновляться нечем.
                403, 401 -> {
                    store.clear()
                    return Outcome.SessionDead
                }

                else -> {
                    if (code >= 500 && withinRetryWindow(startedAt)) {
                        delay(backoffMillis(attempt))
                    } else {
                        return Outcome.Offline
                    }
                }
            }
        }
    }

    private fun withinRetryWindow(startedAt: Long): Boolean =
        clock.nowMillis() - startedAt < RETRY_WINDOW_MILLIS

    private fun backoffMillis(attempt: Int): Long =
        (BASE_BACKOFF_MILLIS * (1L shl (attempt - 1).coerceAtMost(4)))
            .coerceAtMost(MAX_BACKOFF_MILLIS)

    private companion object {
        /**
         * Страховка сервера — минута с момента ротации. Берём с запасом вниз: за пределами окна тот
         * же токен читается как кража и гасит всю сессию.
         */
        const val RETRY_WINDOW_MILLIS = 45_000L
        const val BASE_BACKOFF_MILLIS = 400L
        const val MAX_BACKOFF_MILLIS = 5_000L
    }
}
