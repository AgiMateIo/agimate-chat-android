package ru.agimate.mobile.core.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Вход открывается **только** в Custom Tabs. Встроенный WebView использовать нельзя: Google
 * отклоняет в нём OAuth (`disallowed_useragent`), то есть путь ломается на первом же провайдере
 * из четырёх.
 */
object CustomTabs {

    fun open(context: Context, uri: Uri, toolbarColor: Int, darkToolbarColor: Int): Boolean = try {
        CustomTabsIntent.Builder()
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build()
            )
            .setColorSchemeParams(
                CustomTabsIntent.COLOR_SCHEME_DARK,
                CustomTabColorSchemeParams.Builder().setToolbarColor(darkToolbarColor).build(),
            )
            .build()
            .launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        // Браузера на устройстве нет вообще — редкий, но возможный случай на кастомных прошивках.
        false
    }
}
