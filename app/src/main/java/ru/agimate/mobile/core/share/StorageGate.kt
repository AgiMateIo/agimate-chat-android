package ru.agimate.mobile.core.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Пропуск к общей памяти телефона.
 *
 * С Android 10 сохранять свои файлы в галерею и «Загрузки» можно без разрешений вовсе — там это
 * делается через MediaStore. До Android 10 общая память была обычными каталогами, и без
 * `WRITE_EXTERNAL_STORAGE` запись не удастся; разрешение спрашивается в момент, когда человек уже
 * нажал «Сохранить», — тогда вопрос понятен без объяснений.
 *
 * Возвращает обёртку: `save { viewModel.save(file) }` сам решит, спросить сперва или сохранять
 * сразу.
 */
@Composable
fun rememberSaveGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val denied by rememberUpdatedState(onDenied)

    // Действие переживает диалог разрешения: пока он на экране, сохранять нечего и некуда.
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pending.value
        pending.value = null
        if (granted) action?.invoke() else denied()
    }

    // Обёртка запоминается: она едет в колбэки экрана, и новая на каждую перерисовку заставляла бы
    // перерисовываться всё, что её держит.
    return remember(context, launcher) { { action: () -> Unit ->
        val allowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            action()
        } else {
            pending.value = action
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    } }
}
