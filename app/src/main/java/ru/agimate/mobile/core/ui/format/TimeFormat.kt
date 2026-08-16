package ru.agimate.mobile.core.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Время в списках и в ленте. Показываем в зоне устройства: сервер оперирует моментами, а человек —
 * своими часами.
 */
object TimeFormat {

    private val russian: Locale = Locale.forLanguageTag("ru")
    private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm", russian)
    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMM", russian)
    private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy", russian)

    /** Короткая метка для строки списка: сегодня — часы, вчера — словом, дальше — дата. */
    fun listStamp(
        moment: Instant?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        if (moment == null) return ""
        val local = moment.atZone(zone)
        val date = local.toLocalDate()
        return when {
            date == today -> timeOfDay.format(local)
            date == today.minusDays(1) -> "вчера"
            date.year == today.year -> dayAndMonth.format(local)
            else -> fullDate.format(local)
        }
    }

    /** Время под сообщением в ленте. */
    fun messageStamp(moment: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        moment?.atZone(zone)?.let(timeOfDay::format).orEmpty()

    /** Разделитель дня в ленте сообщений. */
    fun daySeparator(
        moment: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val date = moment.atZone(zone).toLocalDate()
        return when {
            date == today -> "сегодня"
            date == today.minusDays(1) -> "вчера"
            date.year == today.year -> dayAndMonth.format(moment.atZone(zone))
            else -> fullDate.format(moment.atZone(zone))
        }
    }

    /** «Последний вход» в списке устройств. */
    fun dateTime(moment: Instant?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (moment == null) return "—"
        val local = moment.atZone(zone)
        return "${dayAndMonth.format(local)}, ${timeOfDay.format(local)}"
    }
}
