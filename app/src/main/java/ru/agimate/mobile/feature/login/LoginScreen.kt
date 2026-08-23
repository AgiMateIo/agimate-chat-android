package ru.agimate.mobile.feature.login

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import ru.agimate.mobile.LoginPhase
import ru.agimate.mobile.LoginUiState
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.ui.components.BrandMark
import ru.agimate.mobile.core.ui.components.SecondaryButton
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Вход: логотип, строка обещания, кнопки провайдеров. Ни форм, ни паролей, ни отдельной регистрации.
 *
 * Ссылка на интро — единственная дорога назад к рассказу о приложении: интро показывается один раз,
 * и без неё пропустивший его человек не увидит рассказ уже никогда.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onProvider: (AuthProvider) -> Unit,
    onIntro: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = AgiTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgiTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))

            BrandMark(size = 64.dp, color = colors.accent)

            Spacer(Modifier.height(AgiTheme.spacing.lg))

            Text(
                text = "AgiMate",
                style = AgiTheme.typography.title,
                color = colors.textPrimary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.sm))

            Text(
                text = stringResource(R.string.login_tagline),
                style = AgiTheme.typography.secondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xxl))

            if (state.phase == LoginPhase.Exchanging) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(AgiTheme.spacing.md))
                    Text(
                        text = stringResource(R.string.login_finishing),
                        style = AgiTheme.typography.secondary,
                        color = colors.textSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
                ) {
                    AuthProvider.entries.forEach { provider ->
                        SecondaryButton(
                            text = stringResource(
                                R.string.login_with_provider,
                                stringResource(provider.titleRes),
                            ),
                            enabled = state.phase == LoginPhase.Idle,
                            onClick = { onProvider(provider) },
                        )
                    }
                }
            }

            if (state.message != null) {
                Spacer(Modifier.height(AgiTheme.spacing.lg))
                Text(
                    text = state.message.resolve(),
                    style = AgiTheme.typography.secondary,
                    color = colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.xxl))
        }

        // Две дороги в стороне от главного действия: назад к рассказу и в настройки телефона.
        // Указатель влево, а не «открыть», — интро стояло до входа, и ссылка ведёт туда, откуда
        // человек пришёл. Обе лежат поверх прокрутки, а не внутри неё, иначе уедут вместе со
        // списком провайдеров, и обе прячутся на время обмена кода: уводить с экрана, который
        // вот-вот доделает вход, незачем.
        if (state.phase != LoginPhase.Exchanging) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onIntro)
                    .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(AgiTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.login_intro),
                    style = AgiTheme.typography.action,
                    color = colors.textSecondary,
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
