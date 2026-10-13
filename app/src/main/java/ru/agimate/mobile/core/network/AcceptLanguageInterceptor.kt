package ru.agimate.mobile.core.network

import android.os.LocaleList
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `Accept-Language` на каждый запрос к API.
 *
 * Приложение умеет говорить на языке, выбранном человеком, а сервер про этот выбор не знал вовсе:
 * нотисы агента бэкенд берёт из `messages*.properties` по настройке развёртывания — то есть человек
 * с английским интерфейсом получал русский текст. control-api читает заголовок для языка системного
 * контента (`ContentLanguageResolver`): берёт первый поддержанный язык по весу и сравнивает только
 * основной субтег — поэтому региональный `en-US` и хвост из языков телефона ему не мешают. Пока от
 * этого зависят только навыки, которые платформа сама привязывает агенту без пресета; галерея
 * пресетов и навыки отдаются на языке развёртывания.
 *
 * Заодно язык уезжает вместе с регистрацией подписки на пуши — она идёт этим же клиентом, и
 * отдельного поля в теле для этого не нужно.
 *
 * Язык берётся из [Locale.getDefault], а не из хранилища выбора: он верен на обеих ветках — до
 * Android 13 его выставляет `AppLanguages`, с Android 13 система. Дальше идут предпочтения телефона
 * по убыванию: выбор «как в системе» — это именно их порядок.
 */
@Singleton
class AcceptLanguageInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            request.newBuilder()
                .header(HEADER, languages())
                .build()
        )
    }

    /**
     * Собирается на каждый запрос, а не однажды: клиент живёт от старта процесса, а язык меняется
     * на ходу — и выбором в профиле, и настройками телефона.
     */
    private fun languages(): String = acceptLanguage(
        preferred = Locale.getDefault().toLanguageTag(),
        system = LocaleList.getDefault().toLanguageTags().split(','),
    )

    private companion object {
        const val HEADER = "Accept-Language"
    }
}

/**
 * Значение заголовка из выбранного языка и списка предпочтений телефона.
 *
 * Отдельной функцией, потому что вся содержательная часть здесь: порядок, вес и то, что выбранный
 * язык не должен появиться в списке дважды. Android в этом не участвует — значит, и проверять это
 * можно без него.
 */
internal fun acceptLanguage(preferred: String, system: List<String>): String {
    val rest = system
        .map(String::trim)
        .filter { it.isNotEmpty() && it != preferred }
        .distinct()
        .take(MAX_FALLBACKS)
    // Вес по убыванию, а не голый порядок: без `q` все языки списка формально равноценны, и
    // разбирать его сервер вправе как угодно.
    return (listOf(preferred) + rest)
        .mapIndexed { index, tag -> if (index == 0) tag else "$tag;q=0.${10 - index}" }
        .joinToString(", ")
}

/** Дальше третьего запасного языка список ничего не сообщает, а заголовок растёт. */
private const val MAX_FALLBACKS = 3
