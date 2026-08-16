package ru.agimate.mobile.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Бэкенд отдаёт время в трёх разных видах, и это не опечатка в одном месте, а три разных источника:
 *
 * 1. история и листинги — локальное без зоны: `2026-08-15T16:44:13.310006`;
 * 2. события Centrifugo — `Instant.now().toString()`, то есть ISO-8601 UTC с `Z`;
 * 3. профиль пользователя — `yyyy-MM-dd HH:mm:ss` (там на поле висит `@JsonFormat`).
 *
 * Одно и то же сообщение приходит и по HTTP (вид 1), и по WebSocket (вид 2) с одинаковым моментом —
 * значит, вид без зоны сервер пишет в UTC. На этом и стоим: зонные считаем как есть, беззонные
 * трактуем как UTC. Если бэкенд когда-нибудь переедет на локальную зону, поедут ровно эти строки.
 */
object ServerTime {

    private val PROFILE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(raw: String): Instant {
        val value = raw.trim()

        // Вид 2: с зоной или суффиксом Z — момент известен точно.
        if (value.endsWith("Z") || ZONED_TAIL.containsMatchIn(value)) {
            runCatching { return Instant.parse(value) }
            runCatching { return java.time.OffsetDateTime.parse(value).toInstant() }
        }

        // Вид 1: ISO без зоны.
        runCatching { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }

        // Вид 3: профиль, через пробел.
        runCatching { return LocalDateTime.parse(value, PROFILE_FORMAT).toInstant(ZoneOffset.UTC) }

        throw DateTimeParseException("Неизвестный формат времени: $value", value, 0)
    }

    /** Смещение вида `+03:00` в хвосте строки — отличает зонное время от беззонного. */
    private val ZONED_TAIL = Regex("""[+-]\d{2}:?\d{2}$""")
}

/**
 * Все временные поля API десериализуются в [Instant]: экраны считают «сколько прошло» и
 * форматируют в зоне устройства, а для этого нужен момент, а не календарная запись.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ru.agimate.ServerInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = ServerTime.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }
}
