package ru.agimate.mobile.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Повтор на транзиентных ответах: `5xx` и `429`.
 *
 * **Только для идемпотентных запросов.** Отправка сообщения — `POST`, и синхронный: сервер
 * маршрутизирует внутри запроса, ответ может идти до полутора минут, и таймаут прокси на этом фоне
 * легко принять за сбой. Слепой повтор задвоил бы реплику в переписке, а этого не исправить ничем.
 * Поэтому повторяются только `GET` и `HEAD` — то есть листинги и история, где повтор безвреден.
 *
 * `OkHttp` со своим `retryOnConnectionFailure` закрывает только обрывы соединения; `502` от прокси
 * и `429` от лимитера доходят до экрана ошибкой, хотя следующая попытка обычно проходит.
 *
 * Ретраи идут во внешнем интерцепторе, до подстановки origin и `Authorization`: каждая попытка
 * получает свежий заголовок, а не копию просроченного.
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in RETRY_SAFE_METHODS) return chain.proceed(request)

        var lastError: IOException? = null
        var response: Response? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            if (chain.call().isCanceled()) {
                response?.close()
                throw IOException("Запрос отменён")
            }
            if (attempt > 0) {
                // Тело предыдущего ответа надо закрыть, иначе соединение не вернётся в пул.
                response?.close()
                response = null
                if (!sleep(INITIAL_DELAY_MS shl (attempt - 1))) {
                    throw IOException("Повтор прерван")
                }
            }
            try {
                val result = chain.proceed(request)
                if (result.code != 429 && result.code !in 500..599) return result
                response = result
            } catch (e: IOException) {
                lastError = e
            }
        }

        return response ?: throw (lastError ?: IOException("Запрос не удался"))
    }

    /** @return `false`, если ожидание прервали — тогда повторять уже нечего. */
    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {
        val RETRY_SAFE_METHODS = setOf("GET", "HEAD")
        const val MAX_ATTEMPTS = 3
        const val INITIAL_DELAY_MS = 500L
    }
}
