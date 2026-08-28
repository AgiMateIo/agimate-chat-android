package ru.agimate.mobile.feature.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.AuthField
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop

/** Два экрана, отличающиеся тремя строками и одним полем: заявка на регистрацию и просьба о пароле. */
enum class LetterMode { Register, Password }

/**
 * Форма, которая заканчивается письмом.
 *
 * Пароль в приложении не называется ни в одном из двух случаев, и это не упрощение: адрес
 * доказывает тот, кто откроет письмо, а называть пароль до этого значит отдавать аккаунт тому, кто
 * ящиком не владеет. Ссылка из письма ведёт на веб, оттуда человек возвращается сюда и входит
 * обычной формой.
 *
 * Ответ сервера одинаков всегда — адрес свободен, занят или ему уже слали пять писем за час.
 * Поэтому экран говорит «если адрес можно зарегистрировать, письмо отправлено», а не «письмо
 * отправлено»: второе было бы обещанием, которого сервер не давал.
 */
@Composable
fun LetterScreen(mode: LetterMode, onBack: () -> Unit) {
    val colors = AgiTheme.colors
    val viewModel: LoginViewModel = hiltViewModel()
    val form by viewModel.letter.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    val submit = {
        keyboard?.hide()
        when (mode) {
            LetterMode.Register -> viewModel.submitRegistration()
            LetterMode.Password -> viewModel.submitPasswordLetter()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
            // Клавиатура и навигационная полоса претендуют на один и тот же низ: берём большее из
            // двух, а не сумму.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
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
                text = stringResource(
                    if (mode == LetterMode.Register) R.string.register_title
                    else R.string.forgot_title
                ),
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
            Text(
                text = stringResource(
                    if (mode == LetterMode.Register) R.string.register_subtitle
                    else R.string.forgot_subtitle
                ),
                style = AgiTheme.typography.secondary,
                color = colors.textSecondary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xl))

            if (form.sent) {
                Sent(mode = mode, email = form.email, onResend = viewModel::resendConfirmation)
            } else {
                AuthField(
                    value = form.email,
                    onValueChange = viewModel::onLetterEmail,
                    label = stringResource(R.string.login_email_label),
                    keyboardType = KeyboardType.Email,
                    imeAction = if (mode == LetterMode.Register) ImeAction.Next else ImeAction.Go,
                    enabled = !form.busy,
                    onImeAction = if (mode == LetterMode.Register) null else submit,
                )

                if (mode == LetterMode.Register) {
                    Spacer(Modifier.height(AgiTheme.spacing.sm))
                    AuthField(
                        value = form.displayName,
                        onValueChange = viewModel::onDisplayName,
                        label = stringResource(R.string.register_name_label),
                        imeAction = ImeAction.Go,
                        enabled = !form.busy,
                        onImeAction = submit,
                    )
                }

                if (form.error != null) {
                    Spacer(Modifier.height(AgiTheme.spacing.sm))
                    Text(
                        text = form.error!!.resolve(),
                        style = AgiTheme.typography.caption,
                        color = colors.danger,
                    )
                }

                Spacer(Modifier.height(AgiTheme.spacing.lg))

                PrimaryButton(
                    text = stringResource(R.string.letter_submit),
                    onClick = submit,
                    enabled = form.canSubmit,
                    busy = form.busy,
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.xxl))
        }
    }
}

/**
 * «Письмо ушло». Повторную отправку показываем только у регистрации: у пароля повторное письмо —
 * это та же кнопка на форме, а у заявки формы человек уже не видит.
 */
@Composable
private fun Sent(mode: LetterMode, email: String, onResend: () -> Unit) {
    val colors = AgiTheme.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.letter_sent_title),
            style = AgiTheme.typography.subtitle,
            color = colors.textPrimary,
        )

        Spacer(Modifier.height(AgiTheme.spacing.sm))

        Text(
            text = stringResource(
                if (mode == LetterMode.Register) R.string.register_sent_note
                else R.string.forgot_sent_note,
                email,
            ),
            style = AgiTheme.typography.secondary,
            color = colors.textSecondary,
        )

        if (mode == LetterMode.Register) {
            Spacer(Modifier.height(AgiTheme.spacing.lg))
            TextLink(text = stringResource(R.string.register_resend), onClick = onResend)
        }
    }
}
