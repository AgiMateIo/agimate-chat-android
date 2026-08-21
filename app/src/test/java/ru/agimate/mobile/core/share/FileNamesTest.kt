package ru.agimate.mobile.core.share

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Имя вложения приходит с сервера, а оттуда — из чужих рук. До `File(dir, name)` оно доезжать
 * таким, как пришло, не должно.
 */
class FileNamesTest {

    /** То же, что лежит в ресурсах: подставляет его вызывающий, правило от языка не зависит. */
    private val FALLBACK = "файл"

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("отчёт.pdf", diskFileName("отчёт.pdf", "pdf", FALLBACK))
    }

    /** Регрессия: иначе «сохранить» пишет файл куда угодно, только не в свою папку. */
    @Test
    fun `a path in the name is cut down to the name`() {
        assertEquals("passwd", diskFileName("../../etc/passwd", null, FALLBACK))
        assertEquals("файл.txt", diskFileName("""C:\Windows\файл.txt""", null, FALLBACK))
    }

    @Test
    fun `characters the file system refuses become underscores`() {
        assertEquals("счёт_2026_05.pdf", diskFileName("счёт:2026|05.pdf", null, FALLBACK))
    }

    @Test
    fun `a name without an extension takes one from the mime type`() {
        assertEquals("снимок.jpg", diskFileName("снимок", "jpg", FALLBACK))
    }

    /** Сервер знает про файл больше, чем его mime: своё расширение главнее выведенного. */
    @Test
    fun `the name keeps its own extension`() {
        assertEquals("данные.csv", diskFileName("данные.csv", "txt", FALLBACK))
    }

    @Test
    fun `a nameless attachment still gets a name`() {
        assertEquals("файл.png", diskFileName(null, "png", FALLBACK))
        assertEquals("файл", diskFileName("   ", null, FALLBACK))
    }

    /** Имя с точки — на Android скрытый файл: сохранённое вложение просто исчезло бы с глаз. */
    @Test
    fun `a leading dot does not make the file hidden`() {
        assertEquals("gitignore", diskFileName(".gitignore", null, FALLBACK))
    }

    @Test
    fun `a very long name is trimmed but keeps its extension`() {
        val saved = diskFileName("и".repeat(300) + ".pdf", null, FALLBACK)
        assertEquals("и".repeat(80) + ".pdf", saved)
    }

    @Test
    fun `a free name is taken as is`() {
        assertEquals("отчёт.pdf", uniqueFileName("отчёт.pdf") { false })
    }

    @Test
    fun `a taken name gets a copy number before the extension`() {
        val busy = setOf("отчёт.pdf", "отчёт (2).pdf")
        assertEquals("отчёт (3).pdf", uniqueFileName("отчёт.pdf", busy::contains))
    }

    @Test
    fun `a name without an extension is numbered at the end`() {
        assertEquals("README (2)", uniqueFileName("README") { it == "README" })
    }
}
