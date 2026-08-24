package ru.agimate.mobile.data.files

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
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
     * Загрузка. **Без слэша на конце** — это создание, а не листинг.
     *
     * Отдаёт то же представление файла, что и листинг, и ключ там `id`. Внутри `parts` сообщения
     * ключ по-прежнему `fileId`: это контракт отправки, он не менялся, и совпадать эти два имени
     * не обязаны — не «причёсывать».
     *
     * Загрузка ничего ни к чему не привязывает: файл лежит в аккаунте, пока его куда-нибудь не
     * приложат. Так и задумано — загрузка предшествует отправке, получателя на этот момент ещё нет.
     *
     * @param file   часть называется ровно `file`.
     * @param origin метка места в интерфейсе, откуда файл пришёл; сохранится как `user:<метка>` и
     *               вернётся в листинге. Алфавит узкий (`[a-z0-9][a-z0-9_-]{0,31}`), иначе 400:
     *               колонка общая с серверным провенансом (`telegram:<id>`, `media:<модель>`), и
     *               префикс `user:` — то, что не даёт загрузке представиться коннектором.
     */
    @Multipart
    @POST("control/manage/files")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part origin: MultipartBody.Part,
    ): ApiEnvelope<StoredFileDto>

    /**
     * @param sessionId файлы одного разговора — и присланные человеком, и отданные агентом, и
     *                  произведённые инструментом внутри него. Это готовая панель вложений
     *                  переписки.
     * @param name      подстрока имени, регистронезависимо. Файлы без имени — фото из мессенджера,
     *                  сгенерированная картинка — при таком фильтре выпадают целиком: `null` не
     *                  сравнивается ни с чем.
     */
    @GET("control/manage/files/")
    suspend fun files(
        @Query("sessionId") sessionId: String?,
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
