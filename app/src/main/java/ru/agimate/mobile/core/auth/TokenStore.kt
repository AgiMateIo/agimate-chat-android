package ru.agimate.mobile.core.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Пара токенов и идентификатор сессии этого устройства.
 *
 * @param sessionId строка этого устройства в списке «мои устройства» — по нему приложение узнаёт
 *                  себя в чужом списке. Не секрет, но лежит рядом, чтобы чиститься вместе со всем.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
)

/**
 * Хранилище токенов. Интерфейс, а не класс, ровно ради одного: логику обновления токенов надо
 * проверять юнит-тестами, а Keystore на JVM недоступен.
 */
interface TokenStore {

    /** Наблюдаемое состояние: очистка при 403 сама уводит приложение на экран входа. */
    val tokens: StateFlow<AuthTokens?>

    fun load(): AuthTokens?

    /** Сохранение атомарное и синхронное — до следующего сетевого запроса. */
    fun save(tokens: AuthTokens)

    fun clear()
}
