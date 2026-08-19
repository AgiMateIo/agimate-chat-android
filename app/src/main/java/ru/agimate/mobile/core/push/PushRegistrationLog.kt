package ru.agimate.mobile.core.push

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Что именно подтверждено серверу и когда.
 *
 * @param session вход, под которым подтверждали. Подписка принадлежит входу: при перевходе тот же
 *   токен надо регистрировать заново, иначе уведомления просто перестанут приходить.
 * @param tokens «транспорт → токен» на момент подтверждения
 */
data class PushConfirmation(
    val session: String,
    val tokens: Map<String, String>,
    val at: Long,
)

/**
 * Память о подтверждённой подписке.
 *
 * Раньше здесь лежала одна отметка времени, и её не хватало дважды. «Когда» не отвечает на вопрос
 * «что»: после ротации токена свежая отметка читалась как «уже подтверждали», хотя на сервере лежал
 * прежний токен. И точкой сверки такая отметка быть не может — поэтому ветки «вошли» и «новый
 * токен» ходили мимо неё, каждая со своей отправкой, и один и тот же токен уезжал дважды.
 *
 * Интерфейс, а не класс: правило сверки проверяется юнит-тестами, а `SharedPreferences` на JVM нет.
 */
interface PushRegistrationLog {

    fun read(): PushConfirmation?

    fun write(confirmation: PushConfirmation)

    /** Вход кончился — подтверждать больше нечего. */
    fun forget()
}

/**
 * Записи от прежней версии (одна отметка времени, без входа и токенов) читаются как «не
 * подтверждали»: после обновления приложение один раз зарегистрирует подписку заново. Это дешевле
 * миграции и честнее её — про тот вход мы всё равно не знаем, тот ли токен лежит на сервере.
 */
@Singleton
class PrefsPushRegistrationLog @Inject constructor(
    @param:ApplicationContext context: Context,
) : PushRegistrationLog {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): PushConfirmation? {
        val session = prefs.getString(KEY_SESSION, null) ?: return null
        val at = prefs.getLong(KEY_CONFIRMED_AT, 0L)
        val tokens = prefs.getString(KEY_TOKENS, null)
            ?.let { runCatching { Json.decodeFromString(TOKENS, it) }.getOrNull() }
        if (at <= 0L || tokens.isNullOrEmpty()) return null
        return PushConfirmation(session = session, tokens = tokens, at = at)
    }

    override fun write(confirmation: PushConfirmation) {
        prefs.edit {
            putString(KEY_SESSION, confirmation.session)
            putString(KEY_TOKENS, Json.encodeToString(TOKENS, confirmation.tokens))
            putLong(KEY_CONFIRMED_AT, confirmation.at)
        }
    }

    override fun forget() {
        prefs.edit { clear() }
    }

    private companion object {
        val TOKENS = MapSerializer(String.serializer(), String.serializer())
        const val FILE = "push"
        const val KEY_SESSION = "session"
        const val KEY_TOKENS = "tokens"
        const val KEY_CONFIRMED_AT = "confirmed_at"
    }
}

/**
 * Сервер сметает подписки, не появлявшиеся 60 дней, поэтому подтверждать чаще раза в сутки незачем:
 * пуш на закрытом приложении поднимает процесс, и подтверждение на каждое уведомление было бы
 * лишним запросом из фона.
 */
const val PUSH_CONFIRM_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

/**
 * Что из желаемого надо отправить серверу. Чистая половина сверки — проверяется без Android.
 *
 * Вход в ключе не для порядка: подписка принадлежит входу, и при перевходе с тем же токеном её надо
 * регистрировать заново. Памятка «этот токен уже слали» без входа сломала бы ровно это.
 *
 * @param invalidBefore починка с экрана устройств: сервер сказал, что подписки нет или токен в ней
 *   устарел, — значит подтверждения **того времени** он опроверг. Отметка — момент, когда его об
 *   этом спросили. Отменять заодно и подтверждения, полученные уже после ответа сервера, нельзя:
 *   именно так починка и сверка отправляли один и тот же токен по разу каждая.
 */
fun pushDue(
    session: String,
    desired: Map<String, String>,
    confirmed: PushConfirmation?,
    now: Long,
    invalidBefore: Long = 0L,
): Map<String, String> {
    if (desired.isEmpty()) return emptyMap()
    val trusted = confirmed?.takeIf { it.session == session && it.at > invalidBefore }
    return when {
        trusted == null -> desired
        pushConfirmationStale(trusted.at, now) -> desired
        else -> desired.filter { (provider, token) -> trusted.tokens[provider] != token }
    }
}

/**
 * Отметки нет вовсе — подтверждаем, не заглядывая в часы: разность с нулём зависит от того, что
 * сейчас на часах, и на устройстве со сбитым временем дала бы «уже подтверждали».
 *
 * Отметка из будущего — это переведённые назад часы, а не подтверждение: наивная арифметика
 * оставила бы подписку без продления, пока время не догонит отметку.
 */
fun pushConfirmationStale(last: Long, now: Long): Boolean =
    last <= 0L || last > now || now - last >= PUSH_CONFIRM_INTERVAL_MS
