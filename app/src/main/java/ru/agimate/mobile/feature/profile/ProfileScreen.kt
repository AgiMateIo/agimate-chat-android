package ru.agimate.mobile.feature.profile

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.ui.components.AgentAvatar
import ru.agimate.mobile.core.ui.components.SecondaryButton
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.theme.AgiTheme

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onBack: () -> Unit,
    onRevoke: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = AgiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
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
                    contentDescription = "Назад",
                    tint = colors.textPrimary,
                )
            }
            Spacer(Modifier.width(AgiTheme.spacing.xs))
            Text(
                text = "Профиль",
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
                        text = state.displayName.ifBlank { "Без имени" },
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

            Text(
                text = "Мои устройства",
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
                    text = state.error ?: "Список пуст",
                    style = AgiTheme.typography.secondary,
                    color = colors.textSecondary,
                )

                else -> Column {
                    state.devices.forEach { device ->
                        DeviceRowView(device = device, onRevoke = { onRevoke(device.id) })
                    }
                }
            }

            if (state.error != null && state.devices.isNotEmpty()) {
                Spacer(Modifier.height(AgiTheme.spacing.md))
                Text(
                    text = state.error,
                    style = AgiTheme.typography.caption,
                    color = colors.danger,
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.md))

            Text(
                text = "Отзыв бьёт по обновлению токенов: у отозванного устройства связь пропадёт " +
                    "в течение часа.",
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
            SecondaryButton(text = "Выйти", onClick = onSignOut)
        }
    }
}

@Composable
private fun DeviceRowView(device: DeviceRow, onRevoke: () -> Unit) {
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
                    text = device.label,
                    style = AgiTheme.typography.body,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (device.isThisDevice) {
                    Spacer(Modifier.width(AgiTheme.spacing.sm))
                    Text(
                        text = "это устройство",
                        style = AgiTheme.typography.caption,
                        color = colors.accent,
                    )
                }
            }
            Text(
                text = buildString {
                    append(if (device.web) "браузер" else "приложение")
                    append(" · ")
                    append(TimeFormat.dateTime(device.lastSeen))
                },
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )
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
                    text = "Отключить",
                    style = AgiTheme.typography.secondary,
                    color = colors.danger,
                    modifier = Modifier.clickable(onClick = onRevoke),
                )
            }
        }
    }
}
