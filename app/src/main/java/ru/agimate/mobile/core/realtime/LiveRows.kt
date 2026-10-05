package ru.agimate.mobile.core.realtime

import java.time.Instant

/**
 * Положить живую строку в список, отсортированный по свежести.
 *
 * Строка приходит целиком, поэтому прежняя с тем же ключом заменяется, а не сливается. Дальше она
 * встаёт перед первой строкой, которая старше, — то есть поднимается, если стала свежее соседей, и
 * остальной порядок не трогает. Строка без времени активности считается самой старой.
 *
 * Повтор того же события даёт тот же список: доставка at-least-once.
 *
 * @param insertIfAbsent строки нет в списке — вставить (`true`) или оставить список как есть: у
 *                       постраничного списка её отсутствие значит «она на другой странице»
 */
fun <T> List<T>.upsertByActivity(
    row: T,
    insertIfAbsent: Boolean,
    key: (T) -> String,
    activity: (T) -> Instant?,
): List<T> {
    val id = key(row)
    val rest = filterNot { key(it) == id }
    if (rest.size == size && !insertIfAbsent) return this

    val at = activity(row)
    val index = if (at == null) -1 else rest.indexOfFirst { other ->
        val otherAt = activity(other)
        otherAt == null || otherAt < at
    }
    return rest.toMutableList().apply { add(if (index < 0) rest.size else index, row) }
}
