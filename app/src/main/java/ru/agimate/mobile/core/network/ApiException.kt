package ru.agimate.mobile.core.network

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import java.io.IOException

/**
 * Ошибки API в терминах, на которые реагирует UI. Разбор HTTP-кодов живёт здесь, чтобы экраны не
 * писали `if (code == 403)` каждый по-своему.
 *
 * Текста для человека здесь нет — есть [text], намерение его показать: язык выбирается на экране,
 * а сюда `Context` не протащить. Отдельно от него живёт `message` из [Exception]: он для логов и
 * стектрейсов, его никто не переводит и никому не показывает.
 */
sealed class ApiException(
    /** Что увидит человек. Разворачивается на экране — см. [UiText]. */
    val text: UiText,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Нет сети или ответ не доехал. Повторяемо. */
    class Offline(cause: Throwable?) :
        ApiException(uiText(R.string.error_offline), "offline", cause)

    /**
     * 403 от control-api. У аккаунта с ролью `GUEST` так отвечает любой запрос к control-api —
     * это не протухшая сессия, а «ждём одобрения». Разлогинивать по такому нельзя.
     */
    class Forbidden(text: UiText, message: String = "forbidden") : ApiException(text, message)

    /** 401 после того, как обновление токенов уже не помогло. Ведёт на экран входа. */
    class Unauthorized(text: UiText, message: String = "unauthorized") : ApiException(text, message)

    /** 400 — сервер объяснил, что не так (лимиты вложений приходят числом в тексте). */
    class BadRequest(text: UiText, message: String = "bad request") : ApiException(text, message)

    class NotFound(text: UiText, message: String = "not found") : ApiException(text, message)

    /** 429 — упёрлись в лимит загрузок. Заголовка `Retry-After` сервер не шлёт. */
    class RateLimited(text: UiText, message: String = "rate limited") : ApiException(text, message)

    class Server(val code: Int, text: UiText) : ApiException(text, "server error $code")

    class Malformed(text: UiText, message: String = "malformed") : ApiException(text, message)

    companion object {
        /**
         * Текст сервера главнее собственного и показывается как есть.
         *
         * Решение осознанное: сервер знает про отказ то, чего клиент знать не может, — лимит
         * вложений с числами, причину отклонения файла. Цена — этот текст не переводится, и в
         * английском интерфейсе он появится на том языке, на котором его прислали. Свой перевод
         * достаётся только тем ответам, где сервер промолчал.
         */
        fun of(code: Int, serverMessage: String?): ApiException {
            val fromServer = serverMessage?.takeIf { it.isNotBlank() }
            val text = fromServer?.let(::uiText) ?: defaultText(code)
            val forLog = "HTTP $code" + (fromServer?.let { ": $it" } ?: "")
            return when (code) {
                400 -> BadRequest(text, forLog)
                401 -> Unauthorized(text, forLog)
                403 -> Forbidden(text, forLog)
                404 -> NotFound(text, forLog)
                429 -> RateLimited(text, forLog)
                else -> Server(code, text)
            }
        }

        /**
         * Запасной текст по коду.
         *
         * Код подставляется только там, где для него есть место. Отдавать его всем строкам подряд
         * заманчиво — лишний аргумент `getString` пропускает молча, — но так строка с процентом в
         * тексте однажды свалила бы форматирование, и повод искали бы долго.
         */
        private fun defaultText(code: Int): UiText = when (code) {
            400 -> uiText(R.string.error_bad_request)
            401 -> uiText(R.string.error_unauthorized)
            403 -> uiText(R.string.error_forbidden)
            404 -> uiText(R.string.error_not_found)
            429 -> uiText(R.string.error_rate_limited)
            in 500..599 -> uiText(R.string.error_server)
            else -> uiText(R.string.error_code, code)
        }
    }
}

/**
 * Любая ошибка — в термины UI.
 *
 * Всё, что не наше и не сетевое, получает общий текст: у неожиданного исключения нет формулировки,
 * годной человеку, а `message` у него разработческий и часто английский. Он и уходит в логи.
 */
fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is IOException -> ApiException.Offline(this)
    else -> ApiException.Malformed(
        text = uiText(R.string.error_unexpected),
        message = message ?: this::class.java.simpleName,
    )
}
