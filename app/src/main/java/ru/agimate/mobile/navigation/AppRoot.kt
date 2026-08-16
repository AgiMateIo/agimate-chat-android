package ru.agimate.mobile.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.agimate.mobile.LoginUiState
import ru.agimate.mobile.core.auth.AppSession
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.components.FullScreenLoading
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.feature.login.LoginScreen
import ru.agimate.mobile.feature.pending.PendingApprovalScreen

/**
 * Корневая развилка приложения. Навигация внутри продукта начинается только у одобренного
 * аккаунта: `GUEST` до продукта не доходит, и подсовывать ему пустой список агентов нельзя —
 * он подумает, что что-то сломалось.
 */
@Composable
fun AppRoot(
    session: AppSession,
    login: LoginUiState,
    origin: String,
    originEditable: Boolean,
    onProvider: (AuthProvider) -> Unit,
    onOriginChange: (String) -> Unit,
    onRefreshSession: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgiTheme.colors.background)
    ) {
        Crossfade(targetState = session, label = "session") { current ->
            when (current) {
                AppSession.Loading -> FullScreenLoading()

                AppSession.SignedOut -> LoginScreen(
                    state = login,
                    origin = origin,
                    originEditable = originEditable,
                    onProvider = onProvider,
                    onOriginChange = onOriginChange,
                )

                is AppSession.AwaitingApproval -> PendingApprovalScreen(
                    displayName = current.profile.displayName,
                    email = current.profile.email,
                    onRefresh = onRefreshSession,
                    onSignOut = onSignOut,
                )

                is AppSession.Unavailable -> ErrorState(
                    message = current.message.ifBlank { "Не удалось связаться с сервером" },
                    onRetry = onRefreshSession,
                )

                is AppSession.Active -> MainGraph(onSignOut = onSignOut)
            }
        }
    }
}
