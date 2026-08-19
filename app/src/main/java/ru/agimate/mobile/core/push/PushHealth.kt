package ru.agimate.mobile.core.push

import ru.agimate.mobile.data.user.PushSubscriptionDto

/**
 * Приходят ли уведомления на это устройство.
 *
 * Отдельного флага сервер не отдаёт и отдать не может: он знает только то, что ему когда-то
 * зарегистрировали, а держит ли SDK прямо сейчас тот же токен — видно лишь на устройстве.
 */
enum class PushHealth {
    /** Подписка есть, и в ней тот самый токен, что сейчас у SDK. */
    Working,

    /** Подписок нет вовсе — сервер не знает, куда слать. */
    Missing,

    /** Подписка есть, но токен в ней устарел: уведомления уходят в никуда. */
    Stale,

    /**
     * Транспорт поднят, но токена у него пока нет. Сразу после входа и после ротации SDK отдаёт
     * пустой список несколько секунд — это незаконченная настройка, а не поломка, и лечить её
     * перерегистрацией нечем: отправлять пока нечего.
     */
    Preparing,

    /** Транспорт не поднят вовсе (сборка без ключей) — судить не о чем. */
    Unknown;

    /** Оба «нет» лечатся одинаково — перерегистрацией, и она идемпотентна. */
    val fixable: Boolean get() = this == Missing || this == Stale
}

/**
 * @param remote блок `push` своей строки из списка входов
 * @param local токены, которые сейчас держит SDK: «транспорт → токен»
 *
 * Спека требует совпадения хотя бы одной записи. Берём строже — чтобы у каждого поднятого
 * транспорта нашлась своя: лишняя перерегистрация ничего не стоит и идемпотентна, а непойманное
 * расхождение стоит уведомлений. Лишние записи на сервере при этом не мешают: их там штатно
 * две-три, пока не отвалится старый токен.
 */
fun pushHealth(remote: List<PushSubscriptionDto>, local: Map<String, String>): PushHealth = when {
    local.isEmpty() -> PushHealth.Preparing
    remote.isEmpty() -> PushHealth.Missing
    local.all { (provider, token) -> remote.any { it.holds(provider, token) } } -> PushHealth.Working
    else -> PushHealth.Stale
}

private fun PushSubscriptionDto.holds(provider: String, token: String): Boolean {
    val sameProvider = this.provider == null || this.provider.equals(provider, ignoreCase = true)
    return sameProvider && maskedToken.matchesPrefixOf(token)
}

/**
 * `maskedToken` — начало токена и многоточие. Многоточие приходит одним символом, но хвост из трёх
 * точек стоит недорого: маска, разобранная неверно, читалась бы как «токен устарел» — и приложение
 * перерегистрировалось бы на каждом открытии экрана.
 */
private fun String?.matchesPrefixOf(token: String): Boolean {
    val prefix = orEmpty().trim().substringBefore('…').removeSuffix("...")
    return prefix.isNotEmpty() && token.startsWith(prefix)
}
