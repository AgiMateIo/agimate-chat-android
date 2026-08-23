package ru.agimate.mobile.core.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Снимок с камеры как вложение.
 *
 * Снимает чужое приложение: `ACTION_IMAGE_CAPTURE` кладёт кадр по адресу, который мы ему дали.
 * Разрешения для этого не нужно, и `CAMERA` в манифесте объявлять нельзя: объявленное, но не
 * выданное, оно роняет тот же интент `SecurityException`'ом, не давая приложению ничего взамен.
 * Разрешение нужно тому, кто открывает камеру сам.
 *
 * Возвращает `null`, когда снимать нечем: предлагать «сделать фото» в этом случае не нужно вовсе.
 */
@Composable
fun rememberPhotoCapture(onFailed: () -> Unit, onPhoto: (Uri) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val taken by rememberUpdatedState(onPhoto)
    val failed by rememberUpdatedState(onFailed)

    // Адрес снимка переживает смерть процесса: камера — тяжёлое приложение, и наше выгружают, пока
    // человек кадрирует. Результат приходит булевым, и восстановить адрес было бы неоткуда.
    val target = rememberSaveable { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = target.value ?: return@rememberLauncherForActivityResult
        target.value = null
        // Пустой файл — это отмена: файл заводим мы, до запуска камеры, и часть прошивок отвечает
        // «снято», не записав ни байта. Отдать такой дальше значит отправить пустое вложение.
        if (ok && context.hasBytes(uri)) taken(uri) else context.discardPhoto(uri)
    }

    val launch = remember(context, launcher) {
        {
            val uri = context.newPhotoFile()
            if (uri == null) {
                failed()
            } else {
                target.value = uri
                try {
                    launcher.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    // Камеру могли удалить в тот промежуток, пока чат был открыт.
                    target.value = null
                    context.discardPhoto(uri)
                    failed()
                }
            }
        }
    }

    // Запрос к системе — по разу на открытый чат: ответ на него меняется только с установкой или
    // удалением приложений.
    val available = remember(context) {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
    }
    return launch.takeIf { available }
}

/**
 * Пустой файл в кэше и адрес к нему для камеры.
 *
 * Право писать по этому адресу выдаёт сам контракт `TakePicture`: он вешает на интент оба флага,
 * и на чтение, и на запись, — раздавать их руками, как советуют старые рецепты, больше не нужно.
 *
 * Расширение обязательно: тип файла `FileProvider` выводит из него, а без типа вложение уедет на
 * сервер безымянным по формату — и приедет обратно тем, что нечем открыть.
 */
private fun Context.newPhotoFile(): Uri? {
    val dir = File(cacheDir, CAMERA_DIR)
    if (!dir.isDirectory && !dir.mkdirs()) return null
    prunePhotos(dir)

    val stamp = LocalDateTime.now().format(STAMP)
    val name = uniqueFileName("$PHOTO_PREFIX$stamp$PHOTO_EXTENSION") { File(dir, it).exists() }
    val file = File(dir, name)
    return runCatching {
        file.createNewFile()
        FileProvider.getUriForFile(this, "$packageName$FILE_PROVIDER_SUFFIX", file)
    }.getOrNull()
}

/** Отменённый или несостоявшийся снимок. Удаляется адресом: файла на руках уже может не быть. */
private fun Context.discardPhoto(uri: Uri) {
    runCatching { contentResolver.delete(uri, null, null) }
}

/**
 * В файле что-то есть. Спрашивается тем же способом, которым размер вложения потом узнает
 * загрузка, — чтобы «не пусто» здесь и «не пусто» там были одним и тем же числом.
 */
private fun Context.hasBytes(uri: Uri): Boolean = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > 0
    } == true
}.getOrDefault(false)

/**
 * Уборка старых снимков.
 *
 * Удалять свой файл сразу после отправки нельзя: вложение висит в поле ввода, пока сообщение не
 * ушло, и адрес ему нужен всё это время. Поэтому по возрасту и при следующем снимке — так же, как
 * [FileStore] чистит отданное наружу.
 */
private fun prunePhotos(dir: File) {
    val deadline = System.currentTimeMillis() - PHOTO_TTL_MILLIS
    dir.listFiles()?.forEach { if (it.lastModified() < deadline) it.delete() }
}

/** Подкаталог кэша под снимки. Он же объявлен в `res/xml/file_paths.xml`. */
private const val CAMERA_DIR = "camera"

/** Суффикс authority у FileProvider. Должен совпадать с манифестом. */
private const val FILE_PROVIDER_SUFFIX = ".files"

/** Имя как у камеры: латиница и время съёмки — оно уедет на сервер и переводу не подлежит. */
private const val PHOTO_PREFIX = "IMG_"
private const val PHOTO_EXTENSION = ".jpg"
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

private const val PHOTO_TTL_MILLIS = 24L * 60 * 60 * 1000
