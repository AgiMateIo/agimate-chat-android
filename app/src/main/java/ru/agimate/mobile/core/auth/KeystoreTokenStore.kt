package ru.agimate.mobile.core.auth

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Токены на диске: шифротекст в обычных `SharedPreferences`, ключ — в Android Keystore и оттуда не
 * выходит. Авто-бэкап выключен в манифесте (`allowBackup="false"` плюс правила извлечения): иначе
 * файл уехал бы в облако и приехал на другое устройство, где ключа нет, — и это в лучшем случае.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    private val state = MutableStateFlow<AuthTokens?>(null)
    private var loaded = false

    override val tokens: StateFlow<AuthTokens?>
        get() {
            load()
            return state.asStateFlow()
        }

    @Synchronized
    override fun load(): AuthTokens? {
        if (loaded) return state.value

        val encoded = prefs.getString(KEY_PAYLOAD, null)
        state.value = encoded
            ?.let(cipher::decrypt)
            ?.split(SEPARATOR)
            // Три поля — пара, сохранённая до того, как рядом лёг срок обновления. Читается как
            // «срок неизвестен»: обновление по 401 работало и без него, и выбрасывать живой вход
            // ради нового поля незачем.
            ?.takeIf { it.size == 3 || it.size == 4 }
            ?.let {
                AuthTokens(
                    accessToken = it[0],
                    refreshToken = it[1],
                    sessionId = it[2],
                    renewAtMillis = it.getOrNull(3)?.toLongOrNull() ?: 0,
                )
            }

        if (encoded != null && state.value == null) {
            // Расшифровать не смогли — держать нечитаемый мусор незачем.
            wipe()
        }
        loaded = true
        return state.value
    }

    @Synchronized
    override fun save(tokens: AuthTokens) {
        val packed = listOf(
            tokens.accessToken,
            tokens.refreshToken,
            tokens.sessionId,
            tokens.renewAtMillis.toString(),
        ).joinToString(SEPARATOR)
        // commit(), а не apply(): новая пара обязана лежать на диске до следующего запроса —
        // потерять её при ротации значит остаться с токеном, которого на сервере уже нет.
        prefs.edit(commit = true) { putString(KEY_PAYLOAD, cipher.encrypt(packed)) }
        loaded = true
        state.value = tokens
    }

    /** 403 на обновлении — стирать всё разом. */
    @Synchronized
    override fun clear() {
        wipe()
        loaded = true
        state.value = null
    }

    private fun wipe() {
        prefs.edit(commit = true) { clear() }
        cipher.dropKey()
    }

    private companion object {
        const val FILE = "agimate.auth"
        const val KEY_PAYLOAD = "payload"
        const val KEY_ALIAS = "agimate.tokens.v1"

        /** Токены — base64url JWT и UUID, символа `|` в них не бывает. */
        const val SEPARATOR = "|"
    }
}
