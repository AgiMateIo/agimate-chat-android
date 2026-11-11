package ru.agimate.mobile.feature.views

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.CustomTabs
import ru.agimate.mobile.core.ui.theme.DarkColors
import ru.agimate.mobile.core.ui.theme.LightColors

/**
 * Открыть ссылку из вью — только Custom Tabs и только `https`.
 *
 * Общий для лаунчера, inline-карточки и полного экрана: адрес уже проверен протоколом, схема и
 * хост перепроверяются здесь второй линией защиты — дальше последний выход из приложения наружу.
 * `null` — ссылка открыта; строка — текст ошибки для страницы.
 */
@Composable
fun rememberViewLinkOpener(): (Uri) -> String? {
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.views_no_browser)
    return remember(context, noBrowser) {
        { uri ->
            val external = uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank()
            if (!external) {
                noBrowser
            } else {
                val opened = CustomTabs.open(
                    context = context,
                    uri = uri,
                    toolbarColor = LightColors.background.toArgb(),
                    darkToolbarColor = DarkColors.background.toArgb(),
                )
                if (opened) null else noBrowser
            }
        }
    }
}
