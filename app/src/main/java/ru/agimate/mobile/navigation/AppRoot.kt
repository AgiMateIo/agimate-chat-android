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
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.feature.login.LoginScreen
import ru.agimate.mobile.feature.onboarding.OnboardingScreen
import ru.agimate.mobile.feature.pending.PendingApprovalScreen
import ru.agimate.mobile.feature.profile.ProfileScreen
import ru.agimate.mobile.feature.profile.ProfileViewModel
import ru.agimate.mobile.feature.settings.SettingsScreen

/**
 * Корневая развилка приложения. Навигация внутри продукта начинается только у одобренного
 * аккаунта: `GUEST` до продукта не доходит, и подсовывать ему пустой список агентов нельзя —
 * он подумает, что что-то сломалось.
 */
@Composable
fun AppRoot(
    session: AppSession,
    login: LoginUiState,
    onboardingSeen: Boolean,
    pendingChat: PushChatTarget? = null,
    onPendingChatHandled: () -> Unit = {},
    onOnboardingDone: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    onProvider: (AuthProvider) -> Unit,
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

                AppSession.SignedOut -> SignedOut(
                    login = login,
                    onboardingSeen = onboardingSeen,
                    onProvider = onProvider,
                    onOnboardingDone = onOnboardingDone,
                    onReplayOnboarding = onReplayOnboarding,
                )

                is AppSession.AwaitingApproval -> AwaitingApproval(
                    displayName = current.profile.displayName,
                    email = current.profile.email,
                    onRefresh = onRefreshSession,
                    onSignOut = onSignOut,
                )

                is AppSession.Unavailable -> ErrorState(
                    message = current.text.resolve(),
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
 * Экран входа и всё, что открывается с него: интро и настройки.
 *
 * Интро живёт внутри «не вошёл», а не рядом с ним: вошедшему оно поперёк сессии не встанет, даже
 * если отметка о просмотре потерялась вместе с данными приложения.
 *
 * Навигации здесь нет — до входа маршрутов не существует, и заводить граф ради двух ответвлений
 * незачем: у обоих одна дорога назад.
 */
@Composable
private fun SignedOut(
    login: LoginUiState,
    onboardingSeen: Boolean,
    onProvider: (AuthProvider) -> Unit,
    onOnboardingDone: () -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    var settings by rememberSaveable { mutableStateOf(false) }

    if (settings) {
        BackHandler { settings = false }
        SettingsScreen(onBack = { settings = false })
        return
    }

    Crossfade(targetState = onboardingSeen, label = "onboarding") { seen ->
        if (seen) {
            LoginScreen(
                state = login,
                onProvider = onProvider,
                onIntro = onReplayOnboarding,
                onSettings = { settings = true },
            )
        } else {
            OnboardingScreen(onDone = onOnboardingDone)
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
    var open by rememberSaveable { mutableStateOf(Pending.Root) }

    when (open) {
        Pending.Root -> PendingApprovalScreen(
            displayName = displayName,
            email = email,
            onRefresh = onRefresh,
            onDevices = { open = Pending.Devices },
            onSignOut = onSignOut,
        )

        Pending.Settings -> {
            BackHandler { open = Pending.Devices }
            SettingsScreen(onBack = { open = Pending.Devices })
        }

        Pending.Devices -> {
            val viewModel: ProfileViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            BackHandler { open = Pending.Root }
            ProfileScreen(
                state = state,
                onBack = { open = Pending.Root },
                onSettings = { open = Pending.Settings },
                onRevoke = viewModel::revoke,
                onSignOut = onSignOut,
            )
        }
    }
}

/** Три экрана вместо булева флага: с настройками их стало больше, чем «здесь или там». */
private enum class Pending { Root, Devices, Settings }
