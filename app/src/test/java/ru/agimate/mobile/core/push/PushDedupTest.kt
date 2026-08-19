package ru.agimate.mobile.core.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushDedupTest {

    @Test
    fun `повтор того же сообщения не показывается`() {
        val dedup = PushDedup()

        assertTrue(dedup.isNew("m-1"))
        assertFalse(dedup.isNew("m-1"))
    }

    @Test
    fun `разные сообщения проходят`() {
        val dedup = PushDedup()

        assertTrue(dedup.isNew("m-1"))
        assertTrue(dedup.isNew("m-2"))
    }

    @Test
    fun `без идентификатора считаем новым`() {
        val dedup = PushDedup()

        // Молчать хуже, чем показать лишнее: без ключа отличить повтор всё равно нечем.
        assertTrue(dedup.isNew(null))
        assertTrue(dedup.isNew(null))
    }

    @Test
    fun `самое старое вытесняется, недавнее помнится`() {
        val dedup = PushDedup()

        dedup.isNew("m-0")
        repeat(64) { index -> dedup.isNew("m-fill-$index") }

        assertTrue(dedup.isNew("m-0"))
        assertFalse(dedup.isNew("m-fill-63"))
    }
}
