package ru.agimate.mobile.core.onboarding

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Видел ли человек рассказ о приложении.
 *
 * Хранится номер, а не «да»: через год интро может рассказать о чём-то новом, и тогда его нужно
 * показать тем, кто видел старое. С числом это правка одной константы, с флагом — второй ключ и
 * разбирательство, что значит их сочетание.
 *
 * Хранилище обычное, не device-protected — в отличие от языка. Язык читается до первой
 * разблокировки, потому что уведомление поднимает процесс на запертом телефоне; интро же видит
 * только тот, кто уже смотрит на экран.
 */
@Singleton
class OnboardingStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(prefs.getInt(KEY, 0) >= VERSION)

    /** `true` — рассказывать больше нечего, экран входа показывается сразу. */
    val seen: StateFlow<Boolean> = _seen.asStateFlow()

    fun markSeen() {
        prefs.edit { putInt(KEY, VERSION) }
        _seen.value = true
    }

    /**
     * Показать рассказ ещё раз — со ссылки на экране входа.
     *
     * Отметка на диске при этом не трогается: человек попросил перечитать сейчас, а не забыть, что
     * уже читал. Поэтому закрытое на середине приложение в следующий раз откроется на входе, а не
     * снова на интро.
     */
    fun replay() {
        _seen.value = false
    }

    private companion object {
        const val PREFS = "agimate.onboarding"
        const val KEY = "seen_version"

        /** Поднимается, когда интро меняется настолько, что его стоит показать заново. */
        const val VERSION = 1
    }
}
