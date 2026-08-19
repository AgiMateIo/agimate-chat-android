package ru.agimate.mobile.core.push

/**
 * Транспорт пушей: подъём, токены, отзыв.
 *
 * Интерфейс, а не класс, ровно ради одного: решение «что и когда отправлять серверу» проверяется
 * юнит-тестами, а SDK транспорта на JVM недоступен — как и Keystore
 * у [ru.agimate.mobile.core.auth.TokenStore].
 */
interface PushTransport {

    /**
     * Поднят ли транспорт вообще. Без идентификатора проекта пушей нет, и это не поломка:
     * приложение работает как раньше, живая лента на месте.
     */
    val configured: Boolean

    /**
     * Слушатели ставятся один раз на процесс и обязательно из `Application.onCreate`: пуш на
     * закрытом приложении поднимает процесс, и к моменту доставки они должны уже стоять.
     */
    fun start(onMessage: (PushMessage) -> Unit, onToken: (provider: String, token: String) -> Unit)

    /** Токены всех поднятых транспортов: «транспорт → токен». */
    suspend fun tokens(): Map<String, String>

    /** Отзыв токенов у транспорта — чтобы он перестал доставлять на это устройство. */
    suspend fun dropTokens(tokens: Map<String, String>)
}
