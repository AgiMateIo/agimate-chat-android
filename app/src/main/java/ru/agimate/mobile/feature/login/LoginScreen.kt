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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import ru.agimate.mobile.LoginPhase
import ru.agimate.mobile.LoginUiState
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.ui.components.BrandMark
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.components.SecondaryButton
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.auroraBackdrop

/**
 * Вход: логотип, форма с адресом и паролем, кнопки провайдеров под ней.
 *
 * Порядок именно такой. Провайдеры быстрее, но форма — единственный способ войти туда, где
 * провайдер отвязан или его аккаунт на другом ящике; спрятанная под кнопками, она читалась бы как
 * запасной путь, а она равноправная.
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
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    val colors = AgiTheme.colors
    val viewModel: LoginViewModel = hiltViewModel()
    val form by viewModel.signIn.collectAsStateWithLifecycle()
    val mailAvailable by viewModel.mailAvailable.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    val submit = {
        keyboard?.hide()
        viewModel.submitSignIn()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .auroraBackdrop()
            .safeDrawingPadding(),
    ) {
        // Знак и форма стоят посередине экрана. Прокрутка при этом никуда не делась:
        // `fillMaxSize` до `verticalScroll` задаёт колонке минимальную высоту в экран, поэтому
        // центрирование работает, пока содержимое помещается, и уступает прокрутке само, когда
        // поднявшаяся клавиатура или крупный системный шрифт его перерастают.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgiTheme.spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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

            Spacer(Modifier.height(AgiTheme.spacing.xl))

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
                AuthField(
                    value = form.email,
                    onValueChange = viewModel::onEmail,
                    label = stringResource(R.string.login_email_label),
                    keyboardType = KeyboardType.Email,
                    enabled = !form.busy,
                )

                Spacer(Modifier.height(AgiTheme.spacing.sm))

                PasswordField(
                    value = form.password,
                    onValueChange = viewModel::onPassword,
                    label = stringResource(R.string.login_password_label),
                    imeAction = ImeAction.Go,
                    enabled = !form.busy,
                    onImeAction = submit,
                )

                if (form.error != null) {
                    Spacer(Modifier.height(AgiTheme.spacing.sm))
                    Text(
                        text = form.error!!.resolve(),
                        style = AgiTheme.typography.caption,
                        color = colors.danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(AgiTheme.spacing.md))

                PrimaryButton(
                    text = stringResource(R.string.login_submit),
                    onClick = submit,
                    enabled = form.canSubmit,
                    busy = form.busy,
                )

                // Письма нет — прятать надо обе двери сразу: и «забыли», и регистрацию. Обе
                // упираются в одно и то же письмо, и обе на такой установке ведут в отказ.
                if (mailAvailable) {
                    Spacer(Modifier.height(AgiTheme.spacing.md))
                    TextLink(
                        text = stringResource(R.string.login_forgot),
                        onClick = onForgotPassword,
                    )
                }

                Spacer(Modifier.height(AgiTheme.spacing.xl))

                Divider(text = stringResource(R.string.login_or))

                Spacer(Modifier.height(AgiTheme.spacing.lg))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
                ) {
                    AuthProvider.offered.forEach { provider ->
                        SecondaryButton(
                            text = stringResource(
                                R.string.login_with_provider,
                                stringResource(provider.titleRes),
                            ),
                            enabled = state.phase == LoginPhase.Idle && !form.busy,
                            onClick = { onProvider(provider) },
                        )
                    }
                }

                if (mailAvailable) {
                    Spacer(Modifier.height(AgiTheme.spacing.xl))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.login_no_account),
                            style = AgiTheme.typography.secondary,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.width(AgiTheme.spacing.xs))
                        TextLink(
                            text = stringResource(R.string.login_register),
                            onClick = onRegister,
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

            Spacer(Modifier.height(AgiTheme.spacing.xl))
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

/** Текстовая ссылка: второстепенное действие, которому кнопка придала бы лишний вес. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AgiTheme.typography.action,
        color = AgiTheme.colors.accent,
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = AgiTheme.spacing.xs),
    )
}

/** Волосяная линия с надписью посередине: форма и провайдеры — два способа, а не шаги подряд. */
@Composable
private fun Divider(text: String) {
    val colors = AgiTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.hairline)
        )
        Text(
            text = text,
            style = AgiTheme.typography.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = AgiTheme.spacing.md),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.hairline)
        )
    }
}
