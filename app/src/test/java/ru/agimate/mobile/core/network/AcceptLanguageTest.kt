package ru.agimate.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Заголовок — единственное, чем приложение сообщает серверу, на каком языке с ним говорить.
 * Порядок и вес здесь не украшение: по ним бэкенд и выбирает `messages*.properties`.
 */
class AcceptLanguageTest {

    @Test
    fun `выбранный язык идёт первым и без веса`() {
        assertEquals("en", acceptLanguage("en", listOf("en")))
    }

    /** Выбор в профиле против языка телефона: своё — первым, телефонное — как запасное. */
    @Test
    fun `язык телефона остаётся запасным`() {
        assertEquals("en, ru-RU;q=0.9", acceptLanguage("en", listOf("ru-RU")))
    }

    @Test
    fun `вес убывает по списку`() {
        assertEquals(
            "en, ru-RU;q=0.9, de-DE;q=0.8, fr-FR;q=0.7",
            acceptLanguage("en", listOf("ru-RU", "de-DE", "fr-FR")),
        )
    }

    /** Регрессия: `LocaleList` начинается с выбранного языка, и без отсева он шёл бы дважды. */
    @Test
    fun `выбранный язык не повторяется в списке`() {
        assertEquals("ru-RU, en-US;q=0.9", acceptLanguage("ru-RU", listOf("ru-RU", "en-US")))
    }

    @Test
    fun `пустые и повторяющиеся значения отбрасываются`() {
        assertEquals("ru, en;q=0.9", acceptLanguage("ru", listOf(" ", "en", "", "en")))
    }

    /** Хвост списка ничего не сообщает, а заголовок растёт. */
    @Test
    fun `запасных языков не больше трёх`() {
        assertEquals(
            "en, a;q=0.9, b;q=0.8, c;q=0.7",
            acceptLanguage("en", listOf("a", "b", "c", "d", "e")),
        )
    }
}
