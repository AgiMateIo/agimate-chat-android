package ru.agimate.mobile.data.drafts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.agimate.mobile.data.files.PendingAttachment

/**
 * Формат черновиков на диске. Проверяется здесь, а не через [DraftStore]: тот шифрует ключом из
 * Keystore, которого на JVM нет, а правила «что сохраняется» — чистые.
 */
class DraftCodecTest {

    private fun uploaded(fileId: String, name: String = "чек.png") = PendingAttachment(
        localId = "local-$fileId",
        name = name,
        mime = "image/png",
        sizeBytes = 2048,
        uri = null,
        fileId = fileId,
        uploading = false,
    )

    private fun pending() = PendingAttachment(
        localId = "local-pending",
        name = "большой.mp4",
        mime = "video/mp4",
        sizeBytes = 100,
        uri = null,
        fileId = null,
    )

    @Test
    fun `text and uploaded attachments survive the round trip`() {
        val draft = Draft(
            sessionId = "s-1",
            agentId = "a-1",
            text = "посчитай расходы",
            attachments = listOf(uploaded("agf_1")),
            updatedAt = 42,
        )

        val restored = DraftCodec.decode(DraftCodec.encode(listOf(draft)))["s-1"]

        assertEquals("посчитай расходы", restored?.text)
        assertEquals("a-1", restored?.agentId)
        assertEquals(42L, restored?.updatedAt)
        assertEquals("agf_1", restored?.attachments?.single()?.fileId)
        // Ключ строки локальный и заводится заново: сохранять его незачем.
        assertEquals("чек.png", restored?.attachments?.single()?.name)
    }

    /** Недогруженное вложение не доехало бы и после перезапуска: `agf_` у него нет. */
    @Test
    fun `an attachment without a file id is not stored`() {
        val draft = Draft("s-1", "a-1", text = "вот", attachments = listOf(pending(), uploaded("agf_1")))

        val restored = DraftCodec.decode(DraftCodec.encode(listOf(draft)))["s-1"]

        assertEquals(1, restored?.attachments?.size)
        assertEquals("agf_1", restored?.attachments?.single()?.fileId)
    }

    /** Черновик из одного недогруженного вложения не остаётся вовсе — от него ничего не осталось. */
    @Test
    fun `a draft that loses everything is dropped`() {
        val draft = Draft("s-1", "a-1", text = "   ", attachments = listOf(pending()))
        assertNull(DraftCodec.decode(DraftCodec.encode(listOf(draft)))["s-1"])
    }

    @Test
    fun `only the freshest drafts are kept`() {
        val many = (1..DraftCodec.MAX_DRAFTS + 10).map {
            Draft(sessionId = "s-$it", agentId = "a-1", text = "черновик $it", updatedAt = it.toLong())
        }

        val restored = DraftCodec.decode(DraftCodec.encode(many))

        assertEquals(DraftCodec.MAX_DRAFTS, restored.size)
        // Лишними уходят самые давние.
        assertNull(restored["s-1"])
        assertTrue(restored.containsKey("s-${DraftCodec.MAX_DRAFTS + 10}"))
    }

    /** Нечитаемое содержимое — не повод падать: черновики не то, ради чего стоит ронять приложение. */
    @Test
    fun `garbage decodes into nothing`() {
        assertEquals(emptyMap<String, Draft>(), DraftCodec.decode("не json вовсе"))
    }
}
