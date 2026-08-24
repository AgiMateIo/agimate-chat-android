package ru.agimate.mobile.feature.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import ru.agimate.mobile.R
import ru.agimate.mobile.core.push.PushHealth
import ru.agimate.mobile.core.ui.components.AgentAvatar
import ru.agimate.mobile.core.ui.components.SecondaryButton
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAuthMethods: () -> Unit,
    /** Файлы аккаунта. `null` — аккаунт ещё не одобрен, файлов у него взяться неоткуда. */
    onFiles: (() -> Unit)? = null,
    onRevoke: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = AgiTheme.colors

    // Подписка на сервере может быть в полном порядке, а уведомления при этом никто не увидит:
    // разрешение системы снимается и без нас, из настроек телефона.
    val context = LocalContext.current
    val systemAllowed = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AgiTheme.spacing.sm, vertical = AgiTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = colors.textPrimary,
                )
            }
            Spacer(Modifier.width(AgiTheme.spacing.xs))
            Text(
                text = stringResource(R.string.profile_title),
                style = AgiTheme.typography.title,
                color = colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgiTheme.spacing.screen),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(name = state.displayName.ifBlank { state.email.orEmpty() }, size = 52.dp)
                Spacer(Modifier.width(AgiTheme.spacing.md))
                Column {
                    Text(
                        text = state.displayName.ifBlank { stringResource(R.string.profile_no_name) },
                        style = AgiTheme.typography.subtitle,
                        color = colors.textPrimary,
                    )
                    if (!state.email.isNullOrBlank()) {
                        Text(
                            text = state.email,
                            style = AgiTheme.typography.secondary,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(AgiTheme.spacing.xxl))

            // Способы входа — первыми: они про аккаунт, а настройки под ними — про телефон.
            NavRow(title = stringResource(R.string.methods_title), onClick = onAuthMethods)

            // Язык, тема и адрес сервера ушли на свой экран: они принадлежат телефону, а не
            // аккаунту, и нужны ещё до входа — в профиле их было бы не достать.
            NavRow(title = stringResource(R.string.settings_title), onClick = onSettings)

            if (onFiles != null) {
                NavRow(title = stringResource(R.string.files_title), onClick = onFiles)
            }

            Spacer(Modifier.height(AgiTheme.spacing.xl))

            Text(
                text = stringResource(R.string.profile_devices),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.sm))

            when {
                state.loading -> Column(
                    verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md)
                ) {
                    repeat(3) {
                        Skeleton(modifier = Modifier.fillMaxWidth(), height = 44.dp)
                    }
                }

                state.devices.isEmpty() -> Text(
                    text = state.error?.resolve() ?: stringResource(R.string.profile_devices_empty),
                    style = AgiTheme.typography.secondary,
                    color = colors.textSecondary,
                )

                else -> Column {
                    state.devices.forEach { device ->
                        DeviceRowView(
                            device = device,
                            pushNote = pushNote(device, state.pushHealth, systemAllowed),
                            onRevoke = { onRevoke(device.id) },
                        )
                    }
                }
            }

            if (state.error != null && state.devices.isNotEmpty()) {
                Spacer(Modifier.height(AgiTheme.spacing.md))
                Text(
                    text = state.error.resolve(),
                    style = AgiTheme.typography.caption,
                    color = colors.danger,
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.md))

            Text(
                text = stringResource(R.string.profile_revoke_note),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xxl))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(AgiTheme.spacing.screen)
        ) {
            SecondaryButton(text = stringResource(R.string.action_sign_out), onClick = onSignOut)
        }
    }
}

/** @param ok строка про порядок, а не про поломку — от этого зависит только цвет. */
private data class PushNote(@StringRes val text: Int, val ok: Boolean)

/**
 * Признак «уведомления приходят» показываем строкой, а не списком токенов: записей у живого
 * устройства штатно две-три, и человеку они не говорят ничего — их место в обращении в поддержку.
 *
 * Про своё устройство приложение знает точно, сверив маску с живым токеном SDK; про чужое — только
 * то, что подписка есть. Поэтому «не приходят» утверждается лишь о своей строке.
 */
private fun pushNote(device: DeviceRow, health: PushHealth, systemAllowed: Boolean): PushNote? = when {
    !device.isThisDevice ->
        if (device.notifications) PushNote(R.string.profile_push_on, ok = true) else null
    // Транспорта нет вовсе (сборка без ключей) — говорить не о чем.
    health == PushHealth.Unknown -> null
    // Отказ в разрешении — нормальный исход, но «уведомления приходят» после него было бы враньём:
    // сервер отправит, а шторка промолчит.
    !systemAllowed -> PushNote(R.string.profile_push_off_system, ok = false)
    // Токена у транспорта ещё нет: после входа и после ротации SDK отдаёт пустой список несколько
    // секунд. Подписка уедет сама, как только он появится, — «не приходят» здесь пугало бы зря.
    health == PushHealth.Preparing -> PushNote(R.string.profile_push_preparing, ok = true)
    device.notifications -> PushNote(R.string.profile_push_on, ok = true)
    else -> PushNote(R.string.profile_push_off, ok = false)
}

@Composable
private fun DeviceRowView(device: DeviceRow, pushNote: PushNote?, onRevoke: () -> Unit) {
    val colors = AgiTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.label.resolve(),
                    style = AgiTheme.typography.body,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (device.isThisDevice) {
                    Spacer(Modifier.width(AgiTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.profile_this_device),
                        style = AgiTheme.typography.caption,
                        color = colors.accent,
                    )
                }
            }
            Text(
                text = stringResource(
                    if (device.web) R.string.profile_kind_browser else R.string.profile_kind_app
                ) + " · " + TimeFormat.dateTime(device.lastSeen),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )
            if (pushNote != null) {
                Text(
                    text = stringResource(pushNote.text),
                    style = AgiTheme.typography.caption,
                    color = if (pushNote.ok) colors.textTertiary else colors.warning,
                )
            }
        }

        // Своё устройство отозвать нельзя случайно — для выхода есть отдельная кнопка внизу.
        if (!device.isThisDevice) {
            Spacer(Modifier.width(AgiTheme.spacing.md))
            if (device.revoking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.textTertiary,
                )
            } else {
                Text(
                    text = stringResource(R.string.profile_disconnect),
                    style = AgiTheme.typography.secondary,
                    color = colors.danger,
                    modifier = Modifier.clickable(onClick = onRevoke),
                )
            }
        }
    }
}

/** Строка-переход в профиле: их стало больше одной. */
@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    val colors = AgiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AgiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = AgiTheme.typography.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}
