package ru.agimate.mobile.data.files

import kotlinx.serialization.Serializable
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import ru.agimate.mobile.core.network.ApiEnvelope
import ru.agimate.mobile.core.network.InstantSerializer
import ru.agimate.mobile.core.network.PageEnvelope
import java.time.Instant

/**
 * Файлы пользователя — единственное место, где они видны списком.
 *
 * Выдаются только свои, дозалитые и непротухшие: тем же условием, по которому файл потом откроется,
 * — листинг не предлагает того, что откажется скачаться. Порядок серверный, от свежих; параметра
 * сортировки нет.
 *
 * **Слэш в конце пути значим**, как и в вебчате: путь, отдающий список, заканчивается слэшем.
 */
interface FilesApi {

    /**
     * @param agentId файлы, произведённые этим агентом. У загруженных руками и у входящих
     *                `agentId` пуст, так что фильтр их отсекает — это не «файлы переписки».
     * @param name    подстрока имени, регистронезависимо. Файлы без имени — фото из мессенджера,
     *                сгенерированная картинка — при таком фильтре выпадают целиком: `null` не
     *                сравнивается ни с чем.
     */
    @GET("control/manage/files/")
    suspend fun files(
        @Query("agentId") agentId: String?,
        @Query("name") name: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiEnvelope<PageEnvelope<StoredFileDto>>

    /**
     * Удаление раньше срока. На сервере это простановка `expiresAt = now()`, поэтому из выдачи
     * файл пропадает сразу, а ссылки на него в истории переписки перестают открываться — ровно
     * то же, что происходит при истечении срока.
     */
    @DELETE("control/manage/files/{fileId}")
    suspend fun deleteFile(@Path("fileId") fileId: String): ApiEnvelope<String?>
}

@Serializable
data class StoredFileDto(
    val id: String,
    val name: String? = null,
    /** `image | video | audio | file` — выведен сервером из mime. */
    val type: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    val agentId: String? = null,
    val origin: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val expiresAt: Instant? = null,
    /** Подпись выпускается на каждый запрос и живёт минуты — запоминать её нельзя. */
    val url: String? = null,
)
