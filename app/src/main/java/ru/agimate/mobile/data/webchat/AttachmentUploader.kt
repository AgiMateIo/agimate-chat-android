package ru.agimate.mobile.data.webchat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.agimate.mobile.R
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.files.FilesRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Выбранный файл, ещё не отправленный. */
data class PendingAttachment(
    val localId: String,
    /** Пусто у файла с сервера, которому имени никто не дал: подпись подставит экран. */
    val name: String,
    val mime: String?,
    val sizeBytes: Long,
    /**
     * Адрес на устройстве. Пуст у файла, который уже лежит на сервере: у такого сразу есть
     * [fileId], загружать нечего, и локальной копии не существует.
     */
    val uri: Uri?,
    val fileId: String? = null,
    val uploading: Boolean = true,
    val error: UiText? = null,
) {
    val isImage: Boolean get() = mime?.startsWith("image/") == true
}

/**
 * Загрузка вложений.
 *
 * Файл кладётся в общее хранилище файлов пользователя, а не в вебчат: своего эндпойнта загрузки у
 * переписки больше нет. Из этого следует, что загруженное вложение остаётся в аккаунте, даже если
 * сообщение так и не отправили, — и находится потом в списке файлов.
 *
 * Ограничения сервера: 50 МБ на файл и 500 МБ в сутки (оба приходят как 400 с числами в тексте),
 * 30 загрузок в минуту (429; заголовка `Retry-After` нет — паузу подбираем сами).
 */
@Singleton
class AttachmentUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: FilesRepository,
) {
    fun describe(uri: Uri, localId: String): PendingAttachment {
        var name = context.getString(R.string.file_default_name)
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return PendingAttachment(
            localId = localId,
            name = name,
            mime = context.contentResolver.getType(uri),
            sizeBytes = size,
            uri = uri,
        )
    }

    suspend fun upload(attachment: PendingAttachment): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (attachment.sizeBytes > MAX_FILE_BYTES) {
                // Читать 200 МБ в память, чтобы получить в ответ 400, незачем.
                throw ApiException.BadRequest(uiText(R.string.error_file_too_big), "file over limit")
            }
            val uri = attachment.uri
                ?: throw ApiException.BadRequest(uiText(R.string.error_file_unreadable), "no local uri")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw ApiException.BadRequest(uiText(R.string.error_file_unreadable), "unreadable uri")

            try {
                repository.upload(attachment.name, attachment.mime, bytes).id
            } catch (rateLimited: ApiException.RateLimited) {
                // 30 загрузок в минуту; заголовка Retry-After сервер не шлёт, паузу подбираем сами.
                // Одна попытка — дальше это уже не заминка, а разговор с человеком.
                delay(RATE_LIMIT_PAUSE_MILLIS)
                try {
                    repository.upload(attachment.name, attachment.mime, bytes).id
                } catch (_: ApiException.RateLimited) {
                    throw ApiException.RateLimited(
                        uiText(R.string.error_upload_rate_limited),
                        "upload rate limit, retry exhausted",
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_ATTACHMENTS = 5
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        const val RATE_LIMIT_PAUSE_MILLIS = 8_000L
    }
}
