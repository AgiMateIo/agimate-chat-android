package ru.agimate.mobile.data.drafts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.agimate.mobile.data.webchat.PendingAttachment
import java.util.UUID

/**
 * Черновики на диске.
 *
 * На диск уезжает только **загруженное** вложение: у него есть `agf_`, байты уже лежат на сервере,
 * и держать рядом вторую копию незачем. Локальный адрес не сохраняется вовсе — права на content-URI
 * переживают перезапуск процесса не всегда, а недогруженное вложение всё равно не доехало бы.
 *
 * Формат свой, а не общий с API: он живёт на устройстве и меняется по своим поводам. Нечитаемое
 * содержимое даёт пустую карту, а не исключение: черновики — не то, ради чего стоит падать.
 */
internal object DraftCodec {

    /**
     * Сколько черновиков хранится. Держать их вечно нельзя: файл рос бы всю жизнь установки, а
     * ссылки в старых записях всё равно переживают сами файлы. Лишние — самые давние.
     */
    const val MAX_DRAFTS = 50

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(drafts: Collection<Draft>): String = json.encodeToString(
        drafts.asSequence()
            .filterNot { it.isEmpty }
            .sortedByDescending { it.updatedAt }
            .take(MAX_DRAFTS)
            .mapNotNull { it.stored() }
            .toList()
    )

    fun decode(raw: String): Map<String, Draft> = runCatching {
        json.decodeFromString<List<StoredDraft>>(raw)
            .associate { it.sessionId to it.draft() }
            .filterValues { !it.isEmpty }
    }.getOrDefault(emptyMap())

    /** `null` — от черновика ничего не осталось: текста нет, а вложения так и не догрузились. */
    private fun Draft.stored(): StoredDraft? {
        val files = attachments.mapNotNull { attachment ->
            StoredFile(
                fileId = attachment.fileId ?: return@mapNotNull null,
                name = attachment.name,
                mime = attachment.mime,
                size = attachment.sizeBytes,
            )
        }
        if (text.isBlank() && files.isEmpty()) return null
        return StoredDraft(
            sessionId = sessionId,
            agentId = agentId,
            text = text,
            files = files,
            updatedAt = updatedAt,
        )
    }

    private fun StoredDraft.draft() = Draft(
        sessionId = sessionId,
        agentId = agentId,
        text = text,
        attachments = files.map {
            PendingAttachment(
                // Ключ строки в списке, а не идентификатор файла: он локальный и заводится заново.
                localId = UUID.randomUUID().toString(),
                name = it.name,
                mime = it.mime,
                sizeBytes = it.size,
                uri = null,
                fileId = it.fileId,
                uploading = false,
            )
        },
        updatedAt = updatedAt,
    )

    @Serializable
    private data class StoredDraft(
        val sessionId: String,
        val agentId: String? = null,
        val text: String = "",
        val files: List<StoredFile> = emptyList(),
        val updatedAt: Long = 0,
    )

    @Serializable
    private data class StoredFile(
        val fileId: String,
        val name: String = "",
        val mime: String? = null,
        val size: Long = 0,
    )
}
