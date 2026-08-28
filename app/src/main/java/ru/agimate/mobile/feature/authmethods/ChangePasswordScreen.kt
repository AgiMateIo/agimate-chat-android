package ru.agimate.mobile.feature.authmethods

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.PasswordRules
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import ru.agimate.mobile.core.ui.components.PasswordField

/**
 * Смена пароля: текущий и новый.
 *
 * Состояние полей держит экран, а не ViewModel, и это не небрежность: пароль не должен пережить
 * уход с экрана, а всё, что попало во ViewModel, переживает и поворот, и возврат назад.
 */
@Composable
fun ChangePasswordScreen(
    onBack: () -> Unit,
    onSubmit: (current: String, new: String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    val colors = AgiTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current

    var current by rememberSaveable { mutableStateOf("") }
    var fresh by rememberSaveable { mutableStateOf("") }

    val problem = PasswordRules.problem(fresh).takeIf { fresh.isNotEmpty() }
    val ready = !busy && current.isNotEmpty() && fresh.isNotEmpty() && problem == null

    val submit = {
        if (ready) {
            keyboard?.hide()
            onSubmit(current, fresh)
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
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
                text = stringResource(R.string.methods_change_password),
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
                text = stringResource(R.string.password_change_note),
                style = AgiTheme.typography.secondary,
                color = colors.textSecondary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xl))

            PasswordField(
                value = current,
                onValueChange = { current = it },
                label = stringResource(R.string.password_current_label),
                imeAction = ImeAction.Next,
                enabled = !busy,
            )

            Spacer(Modifier.height(AgiTheme.spacing.sm))

            PasswordField(
                value = fresh,
                onValueChange = { fresh = it },
                label = stringResource(R.string.password_new_label),
                imeAction = ImeAction.Go,
                enabled = !busy,
                onImeAction = submit,
            )

            // Длина проверяется на лету, пока человек набирает: сервер посчитает те же байты, и
            // узнать об отказе после нажатия было бы поздно и обиднее.
            if (problem != null) {
                Spacer(Modifier.height(AgiTheme.spacing.xs))
                Text(
                    text = problem.resolve(),
                    style = AgiTheme.typography.caption,
                    color = colors.warning,
                )
            }

            if (error != null) {
                Spacer(Modifier.height(AgiTheme.spacing.sm))
                Text(
                    text = error,
                    style = AgiTheme.typography.caption,
                    color = colors.danger,
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.lg))

            PrimaryButton(
                text = stringResource(R.string.password_change_submit),
                onClick = submit,
                enabled = ready,
                busy = busy,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xxl))
        }
    }
}
