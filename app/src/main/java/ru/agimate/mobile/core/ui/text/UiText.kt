package ru.agimate.mobile.core.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Текст для человека, ещё не превращённый в строку.
 *
 * Сообщения об ошибках и коротких событиях рождаются во ViewModel и в слое данных, а язык там
 * неизвестен: `Context` туда не протащить, не притащив вместе с ним утёкшую Activity и Android в
 * классы, которые сегодня чистый Kotlin. Поэтому наружу едет не строка, а намерение показать
 * строку, и разворачивается оно на экране — там, где локаль уже выбрана системой.
 *
 * Второй повод — сервер. Часть текста приходит готовой из ответа, переводить её нечем и не нам;
 * [Raw] — честное признание этого, а не лазейка.
 */
@Immutable
sealed interface UiText {

    /** Готовая строка снаружи: текст ошибки от сервера, имя файла, название провайдера. */
    @Immutable
    data class Raw(val value: String) : UiText

    /** Строка из ресурсов. Аргументы подставляются при развороте, уже с нужным языком. */
    @Immutable
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
}

/** Короткая запись [UiText.Res]: `uiText(R.string.error_offline)`. */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** Обёртка над готовой строкой. Отдельным именем — чтобы `Raw` в коде бросался в глаза. */
fun uiText(value: String): UiText = UiText.Raw(value)

/**
 * Аргументом строки бывает другая строка из ресурсов — название провайдера в «%s привязан»,
 * например. Вложенный [UiText] разворачивается тем же контекстом, а не подставляется как есть:
 * иначе в тексте оказалось бы `UiText.Res(id=…)`.
 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> context.getString(
        id,
        *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray(),
    )
}

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = resolve(LocalContext.current)
