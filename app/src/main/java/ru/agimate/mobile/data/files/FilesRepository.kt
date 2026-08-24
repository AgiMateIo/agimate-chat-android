package ru.agimate.mobile.data.files

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * Загрузка. Возвращает файл целиком, а не один идентификатор: сервер отдаёт то же
     * представление, что и листинг, и терять его незачем — по нему видно и срок, и выведенный тип.
     *
     * Подписанная ссылка в ответе такая же пятнадцатиминутная, как везде, поэтому для превью сразу
     * после выбора она не годится: там показывается локальный файл, а не сеть.
     */
    suspend fun upload(
        fileName: String,
        mime: String?,
        bytes: ByteArray,
        origin: String = ORIGIN_CHAT,
    ): StoredFile = StoredFile.from(
        apiCall {
            api.upload(
                // Имя части — ровно `file`.
                file = MultipartBody.Part.createFormData(
                    "file",
                    fileName,
                    bytes.toRequestBody(mime?.toMediaTypeOrNull()),
                ),
                origin = MultipartBody.Part.createFormData("origin", origin),
            )
        }.unwrap("загрузка файла")
    )

    suspend fun delete(fileId: String) {
        apiCall { api.deleteFile(fileId) }
    }

    private fun <D, T> PageEnvelope<D>.toPaged(map: (D) -> T) = Paged(
        items = content.map(map),
        isLast = isLastPage,
        totalElements = totalElements,
    )

    companion object {
        /**
         * Откуда файл пришёл. В приложении место загрузки одно — композер переписки, — поэтому и
         * метка одна; в листинге она вернётся как `user:chat`.
         */
        const val ORIGIN_CHAT = "chat"

        private const val PAGE_SIZE = 30
    }
}
