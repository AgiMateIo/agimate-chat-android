package ru.agimate.mobile.core.share

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.agimate.mobile.R
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.FileClient
import ru.agimate.mobile.core.ui.text.uiText
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Файл вложения так, как его видит скачивание: готовый адрес, имя и тип. */
data class RemoteFile(
    /** Идентификатор файла на сервере — им же назван каталог в кэше. */
    val id: String?,
    val url: String,
    val name: String?,
    val mime: String?,
) {
    val isImage: Boolean get() = mime?.startsWith("image/") == true
}

/** Куда лёг сохранённый файл — это же и говорим человеку. */
enum class SavedTo { GALLERY, DOWNLOADS }

/**
 * Скачивание вложения: во временный файл, чтобы отдать другому приложению, или в общую память,
 * чтобы файл остался у человека.
 *
 * Сохранённый файл кладётся в общую память, а не в песочницу приложения: смысл «сохранить» ровно в
 * том, чтобы файл пережил удаление приложения и нашёлся галереей и файловым менеджером.
 */
@Singleton
class FileStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:FileClient private val client: OkHttpClient,
) {

    /**
     * Копия файла в кэше и адрес к ней для чужого приложения.
     *
     * Отдать наружу исходную ссылку нельзя: она подписана, живёт пятнадцать минут и ведёт в наш
     * контур — чужому приложению нужен файл, а не право сходить за ним от нашего имени.
     */
    suspend fun cache(file: RemoteFile): Uri = withContext(Dispatchers.IO) {
        val shared = File(context.cacheDir, SHARED_DIR)
        prune(shared)

        val slot = File(shared, cacheKey(file))
        if (!slot.isDirectory && !slot.mkdirs()) {
            throw ApiException.Malformed(uiText(R.string.error_file_prepare), "cache dir refused")
        }

        val target = File(slot, diskName(file))
        // Файл на сервере неизменен: тот же id — тот же файл, второй раз качать его незачем.
        if (target.length() == 0L) {
            // Пишется рядом и переименовывается: оборвавшаяся закачка оставила бы огрызок, который
            // при следующей попытке был бы принят за готовый файл.
            val part = File(slot, PART_FILE)
            try {
                download(file) { input -> part.outputStream().use(input::copyTo) }
                if (!part.renameTo(target)) {
                    throw ApiException.Malformed(uiText(R.string.error_file_save), "rename failed")
                }
            } finally {
                part.delete()
            }
        }

        FileProvider.getUriForFile(context, authority, target)
    }

    /** Сохранение в общую память: картинки — в галерею, остальное — в «Загрузки». */
    suspend fun save(file: RemoteFile): SavedTo = withContext(Dispatchers.IO) {
        val name = diskName(file)
        val where = if (file.isImage) SavedTo.GALLERY else SavedTo.DOWNLOADS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveThroughMediaStore(file, name, where)
        } else {
            saveIntoPublicDir(file, name, where)
        }
        where
    }

    // ---------------------------------------------------------------- сохранение

    /**
     * Android 10 и новее: файл заводится строкой в MediaStore, и разрешений для этого не нужно —
     * своя запись у приложения есть всегда.
     *
     * `IS_PENDING` держит строку невидимой, пока идёт закачка: иначе галерея успевает показать
     * наполовину скачанную картинку.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveThroughMediaStore(file: RemoteFile, name: String, where: SavedTo) {
        val collection = when (where) {
            SavedTo.GALLERY -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SavedTo.DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val resolver = context.contentResolver
        val row = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            file.mime?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(where))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, row)
            ?: throw ApiException.Malformed(uiText(R.string.error_file_create), "insert returned null")
        try {
            download(file) { input ->
                val output = resolver.openOutputStream(uri)
                    ?: throw ApiException.Malformed(uiText(R.string.error_file_write), "no output stream")
                output.use(input::copyTo)
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Throwable) {
            // Незакрытая запись остаётся в MediaStore навсегда невидимой строкой и местом на диске.
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * До Android 10 общая память — это обычные каталоги, и писать в них можно только с разрешением;
     * его спрашивает [rememberSaveGate] до вызова. Одинаковые имена здесь тоже наша забота:
     * `File` молча перезапишет прежний файл.
     */
    private fun saveIntoPublicDir(file: RemoteFile, name: String, where: SavedTo) {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(publicDir(where))
        val dir = File(root, FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw ApiException.Malformed(uiText(R.string.error_storage_unavailable), "mkdirs failed")
        }

        val target = File(dir, uniqueFileName(name) { File(dir, it).exists() })
        try {
            download(file) { input -> target.outputStream().use(input::copyTo) }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        // Без этого файл лежит на диске, но ни галерея, ни файловый менеджер о нём не знают.
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(file.mime),
            null,
        )
    }

    // ---------------------------------------------------------------- закачка

    private fun <T> download(file: RemoteFile, sink: (InputStream) -> T): T {
        val request = Request.Builder().url(file.url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw refusal(response.code)
            sink(response.body.byteStream())
        }
    }

    /**
     * Ссылка на файл подписана и живёт пятнадцать минут, так что отказ здесь почти всегда означает
     * не «нельзя», а «чат открыт давно». Говорить про доступ в этом случае — врать.
     */
    private fun refusal(code: Int): ApiException = when (code) {
        401, 403 -> ApiException.Forbidden(uiText(R.string.error_file_link_expired), "file link $code")
        404 -> ApiException.NotFound(uiText(R.string.error_file_gone), "file gone")
        else -> ApiException.of(code, null)
    }

    // ---------------------------------------------------------------- мелочи

    private val authority: String get() = context.packageName + FILE_PROVIDER_SUFFIX

    /**
     * Имя каталога в кэше — хэш идентификатора, а не он сам: id приходит с сервера, а из чужой
     * строки не должно получаться ни пути, ни имени. Столкновение хэшей стоит одной лишней
     * закачки, и то раз в жизни.
     */
    private fun cacheKey(file: RemoteFile): String =
        (file.id ?: file.url.substringBefore('?')).hashCode().toUInt().toString(16)

    /**
     * Кэш для «поделиться» — не хранилище: файл нужен ровно на время, пока чужое приложение его
     * забирает. Сутки — с запасом на это и без запаса на то, чтобы копить чужие документы.
     */
    private fun prune(shared: File) {
        val deadline = System.currentTimeMillis() - CACHE_TTL_MILLIS
        shared.listFiles()?.forEach { if (it.lastModified() < deadline) it.deleteRecursively() }
    }

    /** Имя на диске. Запасное имя берётся из ресурсов — оно тоже перевод, а не константа. */
    private fun diskName(file: RemoteFile): String = diskFileName(
        name = file.name,
        extension = extensionOf(file.mime),
        fallback = context.getString(R.string.file_default_name),
    )

    private fun extensionOf(mime: String?): String? =
        mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun relativePath(where: SavedTo) = publicDir(where) + "/" + FOLDER

    private fun publicDir(where: SavedTo) = when (where) {
        SavedTo.GALLERY -> Environment.DIRECTORY_PICTURES
        SavedTo.DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
    }

    private companion object {
        /** Подкаталог кэша под файлы, отданные наружу. Он же объявлен в `res/xml/file_paths.xml`. */
        const val SHARED_DIR = "shared"
        const val PART_FILE = "download.part"

        /** Папка, в которой сохранённые файлы лежат отдельно от чужих. */
        const val FOLDER = "AgiMate"

        /** Суффикс authority у FileProvider. Должен совпадать с манифестом. */
        const val FILE_PROVIDER_SUFFIX = ".files"

        const val CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
