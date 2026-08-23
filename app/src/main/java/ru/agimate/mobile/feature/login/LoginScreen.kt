package ru.agimate.mobile.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
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
    origin: String,
    originEditable: Boolean,
    onProvider: (AuthProvider) -> Unit,
    onOriginChange: (String) -> Unit,
    onIntro: () -> Unit,
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

                Spacer(Modifier.height(AgiTheme.spacing.sm))

                Text(
                    text = stringResource(R.string.login_intro),
                    style = AgiTheme.typography.action,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onIntro)
                        .padding(AgiTheme.spacing.md),
                )
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

            if (originEditable) {
                DevOriginField(origin = origin, onOriginChange = onOriginChange)
            }

            Spacer(Modifier.height(AgiTheme.spacing.xl))
        }
    }
}

/**
 * Только для dev-сборки: с эмулятора бэкенд живёт на 10.0.2.2, с реального телефона — на LAN-IP
 * машины, и перебилживать приложение ради смены адреса незачем.
 */
@Composable
private fun DevOriginField(origin: String, onOriginChange: (String) -> Unit) {
    var value by remember(origin) { mutableStateOf(origin) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.login_origin_label),
            style = AgiTheme.typography.caption,
            color = AgiTheme.colors.textTertiary,
        )
        Spacer(Modifier.height(AgiTheme.spacing.xs))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = AgiTheme.typography.secondary,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onOriginChange(value) }),
        )
    }
}
