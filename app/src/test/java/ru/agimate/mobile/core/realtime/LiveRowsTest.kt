package ru.agimate.mobile.core.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class LiveRowsTest {

    private data class Row(val id: String, val at: Instant?, val label: String = id)

    private fun at(minute: Int) = Instant.parse("2026-09-22T16:%02d:00Z".format(minute))

    private fun List<Row>.put(row: Row, insertIfAbsent: Boolean = true) =
        upsertByActivity(row, insertIfAbsent, key = Row::id, activity = Row::at)

    private val list = listOf(Row("a", at(30)), Row("b", at(20)), Row("c", at(10)))

    @Test
    fun `a fresher row rises to the top`() {
        val result = list.put(Row("c", at(40)))
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
    }

    @Test
    fun `a row is replaced whole, not merged`() {
        val result = list.put(Row("b", at(20), label = "renamed"))
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals("renamed", result[1].label)
    }

    @Test
    fun `a repeated event changes nothing`() {
        val once = list.put(Row("c", at(40)))
        assertEquals(once, once.put(Row("c", at(40))))
    }

    @Test
    fun `an absent row on a paged list is left alone`() {
        assertSame(list, list.put(Row("z", at(50)), insertIfAbsent = false))
    }

    @Test
    fun `an absent row is inserted by freshness when asked to`() {
        val result = list.put(Row("z", at(25)))
        assertEquals(listOf("a", "z", "b", "c"), result.map { it.id })
    }

    @Test
    fun `a row with no activity goes below every dated one`() {
        val result = listOf(Row("a", at(30)), Row("n", null)).put(Row("z", null))
        assertEquals(listOf("a", "n", "z"), result.map { it.id })
    }
}
