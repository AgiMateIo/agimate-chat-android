package ru.agimate.mobile.data.files

import ru.agimate.mobile.core.network.PageEnvelope
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.data.webchat.Paged
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Файл пользователя так, как его показывает список. */
data class StoredFile(
    val id: String,
    val name: String?,
    val mime: String?,
    val size: Long,
    /** Кто произвёл файл. Пусто у загруженных руками и у пришедших в сообщении. */
    val agentId: String?,
    val createdAt: Instant?,
    /** Когда файл исчезнет сам. */
    val expiresAt: Instant?,
    /** Подписанный адрес как его отдал сервер: относительный или абсолютный. */
    val url: String?,
    val isImage: Boolean,
) {
    companion object {
        fun from(dto: StoredFileDto) = StoredFile(
            id = dto.id,
            name = dto.name?.takeIf { it.isNotBlank() },
            mime = dto.mime,
            size = dto.size,
            agentId = dto.agentId,
            createdAt = dto.createdAt,
            expiresAt = dto.expiresAt,
            url = dto.url,
            // Тип сервер уже вывел из mime; свой разбор нужен только на случай, когда поля нет.
            isImage = dto.type == TYPE_IMAGE || dto.mime?.startsWith("image/") == true,
        )

        private const val TYPE_IMAGE = "image"
    }
}

@Singleton
class FilesRepository @Inject constructor(
    private val api: FilesApi,
) {
    /** Пустой фильтр не отправляется вовсе: сервер трактует его как отсутствие, но зачем гадать. */
    suspend fun files(
        agentId: String?,
        name: String?,
        page: Int,
        size: Int = PAGE_SIZE,
    ): Paged<StoredFile> = apiCall {
        api.files(
            agentId = agentId?.takeIf { it.isNotBlank() },
            name = name?.takeIf { it.isNotBlank() },
            page = page,
            size = size,
        )
    }.unwrap("список файлов").toPaged(StoredFile::from)

    suspend fun delete(fileId: String) {
        apiCall { api.deleteFile(fileId) }
    }

    private fun <D, T> PageEnvelope<D>.toPaged(map: (D) -> T) = Paged(
        items = content.map(map),
        isLast = isLastPage,
        totalElements = totalElements,
    )

    private companion object {
        const val PAGE_SIZE = 30
    }
}
