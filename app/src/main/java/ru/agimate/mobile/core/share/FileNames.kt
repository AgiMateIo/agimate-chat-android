package ru.agimate.mobile.core.share

/**
 * Имя файла с сервера в имя файла на диске.
 *
 * Имя вложения приходит из чужих рук: в нём бывает и путь (`../../secrets`), и символы, которых
 * файловые системы Android не принимают. Ни то, ни другое не должно доехать до `File(dir, name)`.
 *
 * Вынесено отдельно и без Android API, чтобы правило проверялось обычным юнит-тестом.
 */

/** Что ломает путь или запрещено FAT на внешней памяти. */
private val FORBIDDEN = Regex("""[\\/:*?"<>|]|\p{Cntrl}""")

/** Имя для файла, у которого своего имени не оказалось. */
private const val FALLBACK = "файл"

/** Дальше имя всё равно обрежет файловая система — лучше обрезать самим и сохранить расширение. */
private const val MAX_STEM = 80

/**
 * Имя, под которым файл ляжет на диск.
 *
 * [extension] — запасное расширение (без точки), выведенное из mime: у файла, названного на сервере
 * просто «снимок», иначе не будет ни превью в галерее, ни приложения, готового его открыть.
 * Собственное расширение имени всегда главнее: сервер знает про файл больше, чем его mime.
 */
internal fun diskFileName(name: String?, extension: String?): String {
    val cleaned = sanitize(name)
    val stem = cleaned.substringBeforeLast('.', cleaned).ifBlank { FALLBACK }.take(MAX_STEM).trim()
    val own = cleaned.substringAfterLast('.', "")
    val ext = own.ifBlank { sanitize(extension) }
    return if (ext.isBlank()) stem else "$stem.$ext"
}

/**
 * Свободное имя в каталоге: `отчёт.pdf` → `отчёт (2).pdf`.
 *
 * Нужно только до Android 10 — MediaStore разводит одинаковые имена сам. Сотня копий одного файла
 * в папке — уже не тот случай, ради которого стоит считать дальше.
 */
internal fun uniqueFileName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val stem = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    for (copy in 2..99) {
        val candidate = if (ext.isBlank()) "$stem ($copy)" else "$stem ($copy).$ext"
        if (!taken(candidate)) return candidate
    }
    return name
}

/**
 * Голое имя без пути и без запрещённых символов.
 *
 * Точки по краям срезаются: имя, начинающееся с точки, на Android — скрытый файл, и сохранённое
 * вложение просто исчезло бы из галереи и из «Загрузок».
 */
private fun sanitize(raw: String?): String = raw.orEmpty()
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .replace(FORBIDDEN, "_")
    .trim()
    .trim('.')
    .trim()
