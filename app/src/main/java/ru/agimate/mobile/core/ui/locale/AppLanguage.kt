package ru.agimate.mobile.core.ui.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.edit
import ru.agimate.mobile.R
import java.util.Locale

/**
 * Язык интерфейса.
 *
 * Список совпадает с `localeFilters` в `app/build.gradle.kts` и с папками `values-*`: предлагать
 * язык, которого нет в ресурсах, значит обещать перевод, которого не существует.
 *
 * Названия языков не переводятся — язык называет себя сам. Человеку, открывшему список на языке,
 * которого он не понимает, «Английский» не поможет, а `English` поможет.
 */
enum class AppLanguage(
    /** BCP 47. `null` — «как в системе»: своего выбора нет, решает телефон. */
    val tag: String?,
    @param:StringRes val titleRes: Int,
) {
    System(null, R.string.language_system),
    Russian("ru", R.string.language_ru),
    English("en", R.string.language_en);

    companion object {
        fun of(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag != null && it.tag == tag } ?: System
    }
}

/**
 * Хранение и применение выбранного языка.
 *
 * Развилка по версии Android здесь не косметическая, а по существу.
 *
 * С Android 13 язык приложения — состояние системы: [LocaleManager] хранит его сам, показывает в
 * настройках телефона отдельным пунктом и сам перезапускает активность. Своей копии заводить
 * нельзя — иначе выбор из наших настроек и выбор из системных разошлись бы, и правым оказался бы
 * тот, кого спросили последним.
 *
 * До Android 13 ничего этого нет, и всё приходится делать самим: помнить выбор, подменять
 * конфигурацию у контекста приложения и активности и пересоздавать экран. Отдельно — [Locale.setDefault]:
 * подмена конфигурации меняет ресурсы, но не глобальную локаль JVM, а по ней форматируются даты.
 *
 * Объект, а не класс с внедрением: [applyTo] зовётся из `attachBaseContext`, то есть раньше, чем
 * Hilt успевает что-либо внедрить.
 */
object AppLanguages {

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppLanguage.of(systemLocaleManager(context)?.applicationLocales?.get(0)?.language)
        } else {
            AppLanguage.of(prefs(context).getString(KEY, null))
        }

    /**
     * Запомнить выбор.
     *
     * Возвращает `true`, если экран нужно пересоздать своими руками. На Android 13+ это делает
     * система, и повторное `recreate()` дало бы второй перезапуск подряд — заметный морганием.
     */
    fun choose(context: Context, language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemLocaleManager(context)?.applicationLocales = localeList(language)
            return false
        }
        prefs(context).edit { putString(KEY, language.tag) }
        applyGlobally(language)
        retrofit(context.applicationContext, language)
        return true
    }

    /**
     * Контекст с выбранным языком — для `attachBaseContext` у приложения и у активности.
     *
     * На Android 13+ конфигурацию уже подменила система, и трогать её здесь значит спорить с ней.
     */
    fun applyTo(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = AppLanguage.of(prefs(base).getString(KEY, null))
        if (language.tag == null) return base

        applyGlobally(language)
        val config = Configuration(base.resources.configuration).apply {
            setLocales(localeList(language))
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Локаль процесса. Ресурсы берутся из конфигурации контекста, а вот `DateTimeFormatter` и
     * `String.format` смотрят на [Locale.getDefault] — без этой строки интерфейс сменил бы язык, а
     * даты под сообщениями остались бы на прежнем.
     *
     * Возврат к «как в системе» — это тоже смена: без неё процесс остался бы на прежнем языке до
     * следующего запуска, и «системный» означал бы «тот, что был выбран до этого».
     */
    private fun applyGlobally(language: AppLanguage) {
        val locale = language.tag?.let(Locale::forLanguageTag) ?: systemLocale()
        Locale.setDefault(locale)
    }

    /**
     * Догнать уже созданный контекст приложения.
     *
     * Экраны переживают смену языка пересозданием, а контекст приложения — нет: он собран при
     * старте процесса и живёт до его конца. Из него берут строки уведомление, диалог «поделиться» и
     * запасное имя файла, и без этой правки они говорили бы на прежнем языке до перезапуска.
     *
     * `updateConfiguration` объявлен устаревшим в пользу `createConfigurationContext`, но тот делает
     * новый контекст, а починить надо существующий. Замены у метода нет — есть только повод, ради
     * которого он и остался.
     */
    @Suppress("DEPRECATION")
    private fun retrofit(application: Context, language: AppLanguage) {
        val resources = application.resources
        val config = Configuration(resources.configuration).apply {
            setLocales(localeList(language).takeIf { !it.isEmpty } ?: systemLocales())
        }
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /** Язык устройства — тот, что был бы без нашего выбора. */
    private fun systemLocale(): Locale = systemLocales()[0] ?: Locale.getDefault()

    private fun systemLocales(): LocaleList = Resources.getSystem().configuration.locales

    private fun localeList(language: AppLanguage): LocaleList =
        language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun systemLocaleManager(context: Context): LocaleManager? =
        context.getSystemService(LocaleManager::class.java)

    /**
     * Выбор языка лежит в device-protected storage, а не рядом с остальными настройками.
     *
     * Обычные `SharedPreferences` до первой разблокировки после перезагрузки не открываются вовсе —
     * обращение к ним бросает исключение. А [applyTo] зовётся из `attachBaseContext`, то есть на
     * каждом старте процесса, включая тот, который подняло пуш-сообщение на запертом телефоне.
     *
     * Хранилище выбрано не только ради этого: язык не секрет, а уведомлению, пришедшему на локскрин
     * до разблокировки, надо знать, на каком языке говорить.
     */
    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "agimate.language"
    private const val KEY = "language"
}
