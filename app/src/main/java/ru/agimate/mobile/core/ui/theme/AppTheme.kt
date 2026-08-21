package ru.agimate.mobile.core.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.core.content.edit
import ru.agimate.mobile.R

/**
 * Тема интерфейса.
 *
 * Состояний ровно три, как на вебе: без явного выбора приложение следует телефону, явный выбор
 * телефон перебивает. «Как в системе» — это отсутствие выбора, а не третье его значение.
 */
enum class AppTheme(
    /** `null` — «как в системе»: своего выбора нет, решает телефон. */
    val tag: String?,
    @param:StringRes val titleRes: Int,
) {
    System(null, R.string.theme_system),
    Light("light", R.string.theme_light),
    Dark("dark", R.string.theme_dark);

    companion object {
        fun of(tag: String?): AppTheme = entries.firstOrNull { it.tag != null && it.tag == tag } ?: System
    }
}

/**
 * Хранение и применение выбранной темы.
 *
 * В отличие от языка, здесь развилки по версии Android нет и хранит выбор приложение. Языку она
 * нужна потому, что с Android 13 язык приложения стал состоянием системы и показывается в
 * настройках телефона отдельным пунктом: своя копия рядом с ним разошлась бы. Темы приложения в
 * настройках телефона не существует ни на одной версии — расходиться не с чем, и заводить ради
 * этого две реализации незачем.
 *
 * Применяется подменой конфигурации, а не флагом в Compose: от `uiMode` зависит не только наша
 * палитра, но и выбор ресурсов `values-night` — фон окна берётся оттуда. Флаг перекрасил бы
 * содержимое, оставив окно под ним прежним.
 *
 * Чего это не чинит: подложку стартового окна. Её рисует система до запуска процесса, спросить нас
 * ей негде, и при явно выбранной теме, противоположной системной, холодный старт мигнёт. Лечится
 * только экраном запуска, и это отдельная работа.
 *
 * Объект, а не класс с внедрением: [applyTo] зовётся из `attachBaseContext`, то есть раньше, чем
 * Hilt успевает что-либо внедрить.
 */
object AppThemes {

    private const val PREFS = "ui"
    private const val KEY = "theme"

    fun current(context: Context): AppTheme =
        AppTheme.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /**
     * Запоминает выбор. Возвращает `true` — экран надо пересоздать: конфигурация подменяется в
     * `attachBaseContext`, то есть на следующем создании активности.
     */
    fun choose(context: Context, theme: AppTheme): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, theme.tag) }
        return true
    }

    /**
     * Хранилище здесь обычное, не device-protected, — и это безопасно ровно потому, что зовут
     * отсюда только активность. Уведомлению, которое поднимается на запертом телефоне, тема не
     * нужна: его рисует система в своей.
     */
    fun applyTo(base: Context): Context {
        val night = when (current(base)) {
            AppTheme.System -> return base
            AppTheme.Light -> Configuration.UI_MODE_NIGHT_NO
            AppTheme.Dark -> Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(base.resources.configuration)
        // Тип устройства (телефон, часы, автомобиль) живёт в тех же битах — его надо сохранить.
        config.uiMode = night or (config.uiMode and Configuration.UI_MODE_TYPE_MASK)
        return base.createConfigurationContext(config)
    }
}
