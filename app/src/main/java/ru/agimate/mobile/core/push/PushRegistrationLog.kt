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
 * Значения, отозванные у транспорта при выходе.
 *
 * Отзыв не мгновенный: SDK начинает переподписку, а `getTokens()` в это окно продолжает отдавать то,
 * что лежало в кэше. Зарегистрировав такое значение, приложение просит сервер слать в никуда —
 * ровно это и случилось 20.08.2026, когда после перевхода обе подписки завели мёртвыми токенами и
 * первое же уведомление их снесло.
 *
 * @param at когда отозвали: карантин не вечный, см. [PUSH_REVOCATION_TTL_MS]
 */
data class PushRevocation(
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

    /** Вход кончился — подтверждать больше нечего. Карантин при этом остаётся: он про то же окно. */
    fun forget()

    /** Что отозвали при последнем выходе; null — карантина нет. */
    fun readRevocation(): PushRevocation?

    /** null — снять карантин. */
    fun writeRevocation(revocation: PushRevocation?)
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

    /**
     * Стирается только подтверждение. Карантин отозванных токенов выход переживает намеренно — он
     * заведён ровно ради окна между выходом и следующим входом, и `clear()` уносил бы его первым.
     */
    override fun forget() {
        prefs.edit {
            remove(KEY_SESSION)
            remove(KEY_TOKENS)
            remove(KEY_CONFIRMED_AT)
        }
    }

    override fun readRevocation(): PushRevocation? {
        val at = prefs.getLong(KEY_REVOKED_AT, 0L)
        val tokens = prefs.getString(KEY_REVOKED, null)
            ?.let { runCatching { Json.decodeFromString(TOKENS, it) }.getOrNull() }
        if (at <= 0L || tokens.isNullOrEmpty()) return null
        return PushRevocation(tokens = tokens, at = at)
    }

    override fun writeRevocation(revocation: PushRevocation?) {
        prefs.edit {
            if (revocation == null) {
                remove(KEY_REVOKED)
                remove(KEY_REVOKED_AT)
            } else {
                putString(KEY_REVOKED, Json.encodeToString(TOKENS, revocation.tokens))
                putLong(KEY_REVOKED_AT, revocation.at)
            }
        }
    }

    private companion object {
        val TOKENS = MapSerializer(String.serializer(), String.serializer())
        const val FILE = "push"
        const val KEY_SESSION = "session"
        const val KEY_TOKENS = "tokens"
        const val KEY_CONFIRMED_AT = "confirmed_at"
        const val KEY_REVOKED = "revoked_tokens"
        const val KEY_REVOKED_AT = "revoked_at"
    }
}

/**
 * Сервер сметает подписки, не появлявшиеся 60 дней, поэтому подтверждать чаще раза в сутки незачем:
 * пуш на закрытом приложении поднимает процесс, и подтверждение на каждое уведомление было бы
 * лишним запросом из фона.
 */
const val PUSH_CONFIRM_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

/**
 * Сколько держать карантин. Переподписка у SDK занимает секунды, десять минут — с запасом; при этом
 * состояние «SDK упорно отдаёт отозванное» не становится вечным: по истечении срока верим
 * транспорту, а мёртвый токен нам назовёт вендор при первой же отправке.
 */
const val PUSH_REVOCATION_TTL_MS: Long = 10 * 60 * 1000L

/**
 * Желаемое за вычетом того, что отозвали сами. Карантин точечный, по значению: тот же провайдер с
 * другим токеном — это уже свежая выдача, и держать её незачем.
 */
fun pushUsable(
    desired: Map<String, String>,
    revocation: PushRevocation?,
    now: Long,
): Map<String, String> {
    if (revocation == null || pushRevocationExpired(revocation.at, now)) return desired
    return desired.filterNot { (provider, token) -> revocation.tokens[provider] == token }
}

/** Отметка из будущего — переведённые назад часы, а не карантин: та же осторожность, что у памятки. */
fun pushRevocationExpired(at: Long, now: Long): Boolean =
    at <= 0L || at > now || now - at >= PUSH_REVOCATION_TTL_MS

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
