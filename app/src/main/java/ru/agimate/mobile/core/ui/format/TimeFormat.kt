package ru.agimate.mobile.core.ui.format

import android.text.format.DateFormat
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Время в списках и в ленте. Показываем в зоне устройства: сервер оперирует моментами, а человек —
 * своими часами.
 *
 * Язык берётся из [Locale.getDefault] в момент показа, а не при создании форматтеров: локаль
 * меняется на ходу — и настройками системы, и выбором языка для одного приложения на Android 13+, —
 * а форматтер, собранный однажды, навсегда остался бы на языке, который был при запуске.
 *
 * «Сегодня» и «вчера» словами — уже не форматирование, а перевод, поэтому наружу едет [UiText]:
 * дату эти функции знают, а языка не знают.
 */
object TimeFormat {

    /** Короткая метка для строки списка: сегодня — часы, вчера — словом, дальше — дата. */
    fun listStamp(
        moment: Instant?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): UiText {
        val local = moment?.atZone(zone) ?: return uiText("")
        val date = local.toLocalDate()
        val formats = current()
        return when {
            date == today -> uiText(formats.timeOfDay.format(local))
            date == today.minusDays(1) -> uiText(R.string.time_yesterday)
            date.year == today.year -> uiText(formats.dayAndMonth.format(local))
            else -> uiText(formats.fullDate.format(local))
        }
    }

    /** Время под сообщением в ленте. Здесь только часы — переводить нечего. */
    fun messageStamp(moment: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        moment?.atZone(zone)?.let { current().timeOfDay.format(it) }.orEmpty()

    /** Разделитель дня в ленте сообщений. */
    fun daySeparator(
        moment: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): UiText {
        val local = moment.atZone(zone)
        val date = local.toLocalDate()
        val formats = current()
        return when {
            date == today -> uiText(R.string.time_today)
            date == today.minusDays(1) -> uiText(R.string.time_yesterday)
            date.year == today.year -> uiText(formats.dayAndMonth.format(local))
            else -> uiText(formats.fullDate.format(local))
        }
    }

    /** «Последний вход» в списке устройств. Прочерк — не текст, а знак, и он одинаков везде. */
    fun dateTime(moment: Instant?, zone: ZoneId = ZoneId.systemDefault()): String {
        val local: ZonedDateTime = moment?.atZone(zone) ?: return "—"
        val formats = current()
        return "${formats.dayAndMonth.format(local)}, ${formats.timeOfDay.format(local)}"
    }

    /** Тройка форматтеров одной локали. Собирается заново, только когда локаль сменилась. */
    private class Formats(val locale: Locale) {
        val timeOfDay: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val fullDate: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val dayAndMonth: DateTimeFormatter =
            DateTimeFormatter.ofPattern(dayAndMonthPattern(locale), locale)
    }

    /**
     * Форматтеры пересобираются на смену локали, а не на каждую строку списка: их построение
     * заметно дороже самого форматирования, а перерисовок у ленты много.
     */
    @Volatile
    private var formats: Formats? = null

    private fun current(): Formats {
        val locale = Locale.getDefault()
        val known = formats
        if (known != null && known.locale == locale) return known
        return Formats(locale).also { formats = it }
    }

    /**
     * «День и месяц» так, как их пишут в этой локали: `12 авг.` против `Aug 12` — разница не в
     * названии месяца, а в порядке, и придумывать его самим нельзя.
     *
     * Порядок знает только система, и спрашивается он у Android. В юнит-тестах Android'а нет,
     * заглушка возвращает `null` — тогда берётся день-месяц: он верен для русского и читается как
     * британский для английского. Тесты проверяют разбор дат, а не типографику.
     */
    private fun dayAndMonthPattern(locale: Locale): String =
        DateFormat.getBestDateTimePattern(locale, "dMMM")?.takeIf { it.isNotBlank() } ?: "d MMM"
}
