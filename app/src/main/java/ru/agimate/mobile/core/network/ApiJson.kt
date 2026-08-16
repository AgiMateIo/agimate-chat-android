package ru.agimate.mobile.core.network

import kotlinx.serialization.json.Json
import okio.IOException
import retrofit2.HttpException

/**
 * Одна настройка Json на весь проект: ей же разбираются тела ошибок, поэтому держать её в двух
 * местах нельзя — разъедутся.
 *
 * `ignoreUnknownKeys` обязателен: бэкенд добавляет поля в листинги, не ломая старых клиентов, и
 * падать на незнакомом ключе значит ломаться от совместимого изменения.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = false
    coerceInputValues = true
}

/**
 * Обёртка вокруг вызова API: превращает транспортные исключения в [ApiException], на которые
 * умеют реагировать экраны.
 */
suspend fun <T> apiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        throw ApiException.of(e.code(), e.serverMessage())
    } catch (e: IOException) {
        throw ApiException.Offline(e)
    } catch (e: java.io.IOException) {
        throw ApiException.Offline(e)
    }

fun HttpException.serverMessage(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    if (body.isBlank()) return@runCatching null
    ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), body).error?.message
}.getOrNull()
