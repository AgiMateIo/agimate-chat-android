package ru.agimate.mobile.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.agimate.mobile.data.files.StoredFile

/**
 * Ключ строки на экране файлов — `id`, и повтор роняет `LazyColumn`, а не портит порядок. Отмены
 * догрузки для этого мало: выборка идёт по смещению, а список живой.
 */
class FilesPagingTest {

    @Test
    fun `страница дописывается целиком, когда пересечения нет`() {
        val current = listOf(file("a"), file("b"))

        assertEquals(
            listOf("a", "b", "c", "d"),
            appendFilesPage(current, listOf(file("c"), file("d"))).map { it.id },
        )
    }

    /**
     * Файл добавили на сервере между запросами: окно сдвинулось, и пограничная строка приезжает
     * второй раз.
     */
    @Test
    fun `сдвиг окна не задваивает пограничную строку`() {
        val current = listOf(file("a"), file("b"), file("c"))

        assertEquals(
            listOf("a", "b", "c", "d"),
            appendFilesPage(current, listOf(file("c"), file("d"))).map { it.id },
        )
    }

    @Test
    fun `страница целиком из уже показанного ничего не меняет`() {
        val current = listOf(file("a"), file("b"))

        assertEquals(current, appendFilesPage(current, listOf(file("a"), file("b"))))
    }

    @Test
    fun `в пустой список страница ложится как есть`() {
        val page = listOf(file("a"), file("b"))

        assertEquals(page, appendFilesPage(emptyList(), page))
    }

    /** Повтор внутри самой страницы — тоже повтор: сервер назвал строку дважды, экран не переживёт. */
    @Test
    fun `повтор внутри страницы не проходит дальше первого раза`() {
        assertEquals(
            listOf("a", "b", "c"),
            appendFilesPage(listOf(file("a")), listOf(file("b"), file("c"), file("b"))).map { it.id },
        )
    }

    private fun file(id: String) = StoredFile(
        id = id,
        name = id,
        mime = null,
        size = 0,
        agentId = null,
        createdAt = null,
        expiresAt = null,
        url = null,
        isImage = false,
    )
}
