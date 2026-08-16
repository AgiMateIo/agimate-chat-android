package ru.agimate.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ru.agimate.mobile.core.auth.CustomTabs
import ru.agimate.mobile.core.ui.theme.AgiMateTheme
import ru.agimate.mobile.core.ui.theme.DarkColors
import ru.agimate.mobile.core.ui.theme.LightColors
import ru.agimate.mobile.navigation.AppRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Активность объявлена singleTask: возврат из Custom Tabs приходит сюда, а не поднимает
        // второй экземпляр. Первый intent разбираем здесь, последующие — в onNewIntent.
        viewModel.onRedirect(intent?.data)

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppResumed()
            }
        )

        setContent {
            AgiMateTheme {
                val session by viewModel.session.collectAsStateWithLifecycle()
                val login by viewModel.login.collectAsStateWithLifecycle()
                val origin by viewModel.origin.collectAsStateWithLifecycle()

                AppRoot(
                    session = session,
                    login = login,
                    origin = origin,
                    originEditable = viewModel.originEditable,
                    onProvider = { provider ->
                        val uri = viewModel.beginLogin(provider)
                        val opened = CustomTabs.open(
                            context = this,
                            uri = uri,
                            toolbarColor = LightColors.background.toArgb(),
                            darkToolbarColor = DarkColors.background.toArgb(),
                        )
                        if (!opened) viewModel.onBrowserUnavailable()
                    },
                    onOriginChange = viewModel::changeOrigin,
                    onRefreshSession = viewModel::onAppResumed,
                    onSignOut = viewModel::signOut,
                )

                LaunchedEffect(login.message) {
                    // Сообщение живёт до следующего действия — гасить по таймеру не надо.
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onRedirect(intent.data)
    }
}
