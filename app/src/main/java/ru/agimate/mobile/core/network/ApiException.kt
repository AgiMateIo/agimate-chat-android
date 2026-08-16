package ru.agimate.mobile.core.network

import java.io.IOException

/**
 * Ошибки API в терминах, на которые реагирует UI. Разбор HTTP-кодов живёт здесь, чтобы экраны не
 * писали `if (code == 403)` каждый по-своему.
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Нет сети или ответ не доехал. Повторяемо. */
    class Offline(cause: Throwable?) :
        ApiException("Нет связи с сервером", cause)

    /**
     * 403 от control-api. У аккаунта с ролью `GUEST` так отвечает любой запрос к control-api —
     * это не протухшая сессия, а «ждём одобрения». Разлогинивать по такому нельзя.
     */
    class Forbidden(message: String) : ApiException(message)

    /** 401 после того, как обновление токенов уже не помогло. Ведёт на экран входа. */
    class Unauthorized(message: String) : ApiException(message)

    /** 400 — сервер объяснил, что не так (лимиты вложений приходят числом в тексте). */
    class BadRequest(message: String) : ApiException(message)

    class NotFound(message: String) : ApiException(message)

    /** 429 — упёрлись в лимит загрузок. Заголовка `Retry-After` сервер не шлёт. */
    class RateLimited(message: String) : ApiException(message)

    class Server(val code: Int, message: String) : ApiException(message)

    class Malformed(message: String) : ApiException(message)

    companion object {
        fun of(code: Int, serverMessage: String?): ApiException {
            val text = serverMessage?.takeIf { it.isNotBlank() } ?: defaultText(code)
            return when (code) {
                400 -> BadRequest(text)
                401 -> Unauthorized(text)
                403 -> Forbidden(text)
                404 -> NotFound(text)
                429 -> RateLimited(text)
                else -> Server(code, text)
            }
        }

        private fun defaultText(code: Int) = when (code) {
            400 -> "Запрос отклонён"
            401 -> "Нужно войти заново"
            403 -> "Доступ закрыт"
            404 -> "Не найдено"
            429 -> "Слишком часто — подождите немного"
            in 500..599 -> "Сервер не отвечает"
            else -> "Ошибка $code"
        }
    }
}

fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is IOException -> ApiException.Offline(this)
    else -> ApiException.Malformed(message ?: this::class.java.simpleName)
}
