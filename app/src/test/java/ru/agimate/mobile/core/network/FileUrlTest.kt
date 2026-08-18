package ru.agimate.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Вид ссылки на файл выбирает сервер, и оба вида приходят вперемешку в одном ответе. Ошибка тихая:
 * склеенный адрес `<origin>/control https://s3.…` даёт не картинку, а пустое место в ленте.
 */
class FileUrlTest {

    private val controlBase = "https://api.agimate.io/control"

    @Test
    fun `a relative url gets the origin and the control prefix`() {
        assertEquals(
            "https://api.agimate.io/control/files/agf_1?exp=1&sig=abc",
            resolveFileUrl("/files/agf_1?exp=1&sig=abc", controlBase),
        )
    }

    @Test
    fun `a relative url without the leading slash still joins with one`() {
        assertEquals(
            "https://api.agimate.io/control/files/agf_1",
            resolveFileUrl("files/agf_1", controlBase),
        )
    }

    /** Регрессия: пресайненную ссылку в хранилище трогать нельзя — подпись уже в ней. */
    @Test
    fun `a presigned storage url is taken as is`() {
        val presigned = "https://s3.cloud.ru/agimate-files/agf_1?X-Amz-Signature=deadbeef"
        assertEquals(presigned, resolveFileUrl(presigned, controlBase))
    }

    @Test
    fun `plain http is absolute too`() {
        assertEquals("http://10.0.2.2:9000/bucket/agf_1", resolveFileUrl("http://10.0.2.2:9000/bucket/agf_1", controlBase))
    }
}
