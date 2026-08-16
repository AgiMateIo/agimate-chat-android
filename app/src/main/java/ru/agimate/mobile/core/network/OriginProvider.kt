package ru.agimate.mobile.core.network

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.agimate.mobile.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адрес бэкенда. В prod он один и зашит в сборку; в dev его меняют руками — с эмулятора это
 * `10.0.2.2`, с реального телефона LAN-IP машины, и перебилживать ради этого приложение незачем.
 *
 * Адрес не секрет и живёт в обычных SharedPreferences: рядом с зашифрованными токенами ему делать
 * нечего — это лишний повод их потерять.
 */
@Singleton
class OriginProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _origin = MutableStateFlow(readStored())
    val origin: StateFlow<String> = _origin.asStateFlow()

    val current: String get() = _origin.value

    /** Базовый адрес user-api: `<origin>/user`. */
    val userBase: String get() = current.trimEnd('/') + "/user"

    /** Базовый адрес control-api: `<origin>/control`. */
    val controlBase: String get() = current.trimEnd('/') + "/control"

    /**
     * Полный адрес содержимого вложения. Поле `url` приходит относительным и **без** префикса
     * `/control` — собирается как `<origin>` + `/control` + `url`.
     */
    fun fileUrl(relativeUrl: String): String =
        controlBase + if (relativeUrl.startsWith("/")) relativeUrl else "/$relativeUrl"

    fun override(value: String): Boolean {
        if (!BuildConfig.ALLOW_ORIGIN_OVERRIDE) return false
        val normalized = normalize(value) ?: return false
        prefs.edit { putString(KEY, normalized) }
        _origin.value = normalized
        return true
    }

    fun reset() {
        prefs.edit { remove(KEY) }
        _origin.value = BuildConfig.API_ORIGIN
    }

    private fun readStored(): String =
        if (BuildConfig.ALLOW_ORIGIN_OVERRIDE) {
            prefs.getString(KEY, null)?.let(::normalize) ?: BuildConfig.API_ORIGIN
        } else {
            BuildConfig.API_ORIGIN
        }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
        }
    }

    private companion object {
        const val PREFS = "agimate.origin"
        const val KEY = "origin"
    }
}
