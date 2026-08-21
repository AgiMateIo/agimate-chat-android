package ru.agimate.mobile.core.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.agimate.mobile.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Всё, чем приложение отдаёт содержимое наружу: буфер обмена и чужие приложения.
 *
 * Интенты только собираются, но не запускаются: `startActivity` нужен контекст экрана, а решение
 * «поделиться» принимается во ViewModel. Готовый интент едет на экран, экран его запускает.
 */
@Singleton
class Sharing @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * С Android 13 система сама показывает, что скопировалось. Своё подтверждение было бы вторым
     * подряд — и о том же самом.
     */
    val clipboardConfirmsItself: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = context.getString(R.string.file_clip_label)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun shareText(text: String): Intent = chooser(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text),
        title = R.string.file_chooser_share_message,
    )

    fun shareFile(uri: Uri, mime: String?): Intent = chooser(
        Intent(Intent.ACTION_SEND)
            .setType(mime ?: ANY_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .grantRead(uri),
        title = R.string.file_chooser_share_file,
    )

    fun openFile(uri: Uri, mime: String?): Intent = chooser(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime ?: ANY_TYPE)
            .grantRead(uri),
        title = R.string.file_chooser_open_file,
    )

    /**
     * Файл из нашего кэша чужому приложению не принадлежит, и без явного права на чтение оно
     * получит по этому адресу отказ. Право едет и на самом интенте, и на диалоге выбора: до
     * выбранного приложения флаги доезжают именно через диалог.
     *
     * `clipData` — не про буфер обмена: часть приложений забирает вложение оттуда, и без него
     * право на чтение до них не доходит.
     */
    private fun Intent.grantRead(uri: Uri): Intent = apply {
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Всегда через диалог выбора: приложения, умеющего открыть этот тип, может не найтись вовсе, и
     * тогда диалог честно скажет об этом, а прямой запуск свалился бы исключением.
     */
    private fun chooser(intent: Intent, @StringRes title: Int): Intent =
        Intent.createChooser(intent, context.getString(title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private companion object {
        const val ANY_TYPE = "*/*"
    }
}
