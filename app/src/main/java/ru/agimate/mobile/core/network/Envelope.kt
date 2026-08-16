package ru.agimate.mobile.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Успешный ответ обоих сервисов всегда завёрнут: `{"response": …}`. */
@Serializable
data class ApiEnvelope<T>(val response: T? = null)

/** Ошибка: `{"error": {"message": "…"}}`. */
@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(val message: String? = null)

/**
 * Постраничный ответ, нумерация с нуля.
 *
 * Потолок [size] — 100: запрос с `size=500` не ошибка, сервер молча вернёт сотню. Поэтому «сколько
 * ещё грузить» считается по полям ответа, а не по тому, что просили.
 */
@Serializable
data class PageEnvelope<T>(
    val content: List<T> = emptyList(),
    val number: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
) {
    val isLastPage: Boolean get() = number >= totalPages - 1 || content.isEmpty()
}

/**
 * Развернуть конверт. Пустой `response` у ответа, от которого ждали тело, — это сломанный контракт,
 * а не «пусто»: списки приходят массивом, страницы — конвертом, и `null` там не бывает.
 */
fun <T> ApiEnvelope<T>.unwrap(what: String): T =
    response ?: throw ApiException.Malformed("Пустой ответ сервера: $what")

@Serializable
data class ProblemMessage(@SerialName("message") val message: String? = null)
