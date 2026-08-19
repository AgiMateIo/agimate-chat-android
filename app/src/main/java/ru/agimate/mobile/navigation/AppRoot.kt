package ru.agimate.mobile.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.agimate.mobile.LoginUiState
import ru.agimate.mobile.core.auth.AppSession
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.push.PushChatTarget
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.components.FullScreenLoading
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.feature.login.LoginScreen
import ru.agimate.mobile.feature.pending.PendingApprovalScreen
import ru.agimate.mobile.feature.profile.ProfileScreen
import ru.agimate.mobile.feature.profile.ProfileViewModel

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
    pendingChat: PushChatTarget? = null,
    onPendingChatHandled: () -> Unit = {},
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

                is AppSession.AwaitingApproval -> AwaitingApproval(
                    displayName = current.profile.displayName,
                    email = current.profile.email,
                    onRefresh = onRefreshSession,
                    onSignOut = onSignOut,
                )

                is AppSession.Unavailable -> ErrorState(
                    message = current.message.ifBlank { "Не удалось связаться с сервером" },
                    onRetry = onRefreshSession,
                )

                is AppSession.Active -> MainGraph(
                    onSignOut = onSignOut,
                    pendingChat = pendingChat,
                    onPendingChatHandled = onPendingChatHandled,
                )
            }
        }
    }
}

/**
 * Ожидание одобрения плюс список входов. Навигации здесь нет и заводить её ради одного экрана
 * незачем: `GUEST` до продукта не доходит, а отозвать потерянный телефон ему нужно уже сейчас —
 * иначе придётся ждать администратора, чтобы дотянуться до чужого устройства.
 */
@Composable
private fun AwaitingApproval(
    displayName: String,
    email: String?,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    var devices by rememberSaveable { mutableStateOf(false) }

    if (!devices) {
        PendingApprovalScreen(
            displayName = displayName,
            email = email,
            onRefresh = onRefresh,
            onDevices = { devices = true },
            onSignOut = onSignOut,
        )
        return
    }

    val viewModel: ProfileViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler { devices = false }
    ProfileScreen(
        state = state,
        onBack = { devices = false },
        onRevoke = viewModel::revoke,
        onSignOut = onSignOut,
    )
}
