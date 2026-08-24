package ru.agimate.mobile.core.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Пара токенов и идентификатор сессии этого устройства.
 *
 * @param sessionId строка этого устройства в списке «мои устройства» — по нему приложение узнаёт
 *                  себя в чужом списке. Не секрет, но лежит рядом, чтобы чиститься вместе со всем.
 * @param renewAtMillis момент, с которого access-токен пора менять заранее, — примерно девять
 *                  десятых его срока. Хранится готовым моментом, а не сроком: считать «сколько
 *                  осталось» пришлось бы всем, кто читает хранилище, и каждый считал бы по-своему.
 *                  Ноль значит «неизвестно» — так выглядит пара, сохранённая прошлой версией
 *                  приложения; для неё остаётся прежний путь, через 401.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val renewAtMillis: Long = 0,
) {
    /** Пора ли обновляться, не дожидаясь 401. Неизвестный срок не повод будить обновление. */
    fun renewalDue(nowMillis: Long): Boolean = renewAtMillis > 0 && nowMillis >= renewAtMillis
}

/**
 * Во что превращается ответ любого из трёх путей входа: формы у них разные, пара токенов — одна.
 *
 * Срок берётся из `expiresIn`, а не из константы: сервер меняет его без предупреждения клиентов.
 * Пустой `expiresIn` (сервер промолчал) даёт неизвестный момент обновления, а не выдуманный час.
 */
fun AuthResponseDto.toTokens(nowMillis: Long): AuthTokens = AuthTokens(
    accessToken = accessToken,
    refreshToken = refreshToken,
    sessionId = sessionId,
    renewAtMillis = if (expiresIn > 0) nowMillis + expiresIn * RENEW_FRACTION_MILLIS else 0,
)

/** Девять десятых срока в миллисекундах на секунду: `expiresIn * 1000 * 0.9`. */
private const val RENEW_FRACTION_MILLIS = 900L

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
