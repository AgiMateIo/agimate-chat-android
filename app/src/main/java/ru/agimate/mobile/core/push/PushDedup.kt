package ru.agimate.mobile.core.push

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Что уже показывали.
 *
 * Доставка at-least-once: одно и то же уведомление приходит дважды при повторе на бэкенде или
 * реплее. Одинаковый notification id не даёт второй строки в шторке, но система на повтор всё
 * равно звенит и всплывает — поэтому дубль отсекается до показа.
 *
 * Память процесса, а не хранилище: повтор приходит в пределах минут, а переживать перезапуск здесь
 * нечему — после него шторка всё равно пуста.
 */
@Singleton
class PushDedup @Inject constructor() {

    private val seen = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean = size > CAPACITY
    }

    /** `true` — это сообщение видим впервые. Без идентификатора считаем новым: молчать хуже. */
    @Synchronized
    fun isNew(messageId: String?): Boolean {
        if (messageId == null) return true
        if (seen.containsKey(messageId)) return false
        seen[messageId] = Unit
        return true
    }

    private companion object {
        /** Хватает с запасом: столько уведомлений подряд не приходит даже в самой живой переписке. */
        const val CAPACITY = 64
    }
}
