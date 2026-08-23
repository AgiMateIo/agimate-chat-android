package ru.agimate.mobile.feature.pending

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.BrandMark
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.components.SecondaryButton
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.auroraBackdrop

/**
 * Роль `GUEST`. Это **не** ошибка авторизации: вход прошёл, токены выданы, а агентов завести пока
 * нельзя. Экран обязан объяснять, чего ждём и сколько примерно, и не выглядеть поломкой.
 */
@Composable
fun PendingApprovalScreen(
    displayName: String,
    email: String?,
    onRefresh: () -> Unit,
    onDevices: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = AgiTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .auroraBackdrop()
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AgiTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(size = 52.dp, color = colors.accent)

            Spacer(Modifier.height(AgiTheme.spacing.xl))

            Text(
                text = stringResource(R.string.pending_title),
                style = AgiTheme.typography.title,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(AgiTheme.spacing.md))

            Text(
                text = stringResource(
                    R.string.pending_body,
                    displayName.ifBlank { email.orEmpty() },
                ),
                style = AgiTheme.typography.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xxl))

            PrimaryButton(
                text = stringResource(R.string.pending_refresh),
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(AgiTheme.spacing.md))

            // Список входов открыт и до одобрения: потерявшему телефон отзыв нужен раньше, чем
            // администратор дойдёт до заявки.
            SecondaryButton(
                text = stringResource(R.string.profile_devices),
                onClick = onDevices,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(AgiTheme.spacing.md))

            SecondaryButton(
                text = stringResource(R.string.action_sign_out),
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
