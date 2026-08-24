package ru.agimate.mobile.feature.authmethods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.auth.CustomTabs
import ru.agimate.mobile.core.ui.components.SecondaryButton
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.DarkColors
import ru.agimate.mobile.core.ui.theme.LightColors
import ru.agimate.mobile.core.ui.theme.backdrop
import androidx.compose.ui.graphics.toArgb

/**
 * Способы попасть в этот аккаунт — и добавление ещё одного.
 *
 * Круг к провайдеру открывается прямо отсюда, а не через корневую развилку, как вход: вход
 * начинается там, где ViewModel'и ещё нет, а привязка — на живом экране, и тащить её через весь
 * граф ради одного вызова браузера значило бы протянуть Custom Tabs сквозь четыре чужих экрана.
 *
 * Экран открыт и аккаунту в ожидании одобрения: отозвать чужую дверь ему нужно не меньше, чем
 * одобренному, а ждать администратора ради этого — плохой ответ.
 */
@Composable
fun AuthMethodsScreen(onBack: () -> Unit) {
    val colors = AgiTheme.colors
    val viewModel: AuthMethodsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var changing by remember { mutableStateOf(false) }

    if (changing) {
        ChangePasswordScreen(
            onBack = { changing = false },
            onSubmit = { current, new ->
                viewModel.changePassword(current, new) { changing = false }
            },
            busy = state.changing,
            error = state.error?.resolve(),
        )
        return
    }

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
                text = stringResource(R.string.methods_title),
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
            if (state.note != null) {
                Text(
                    text = state.note!!.resolve(),
                    style = AgiTheme.typography.secondary,
                    color = colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::dismissNote)
                        .padding(vertical = AgiTheme.spacing.sm),
                )
            }

            if (state.error != null) {
                Text(
                    text = state.error!!.resolve(),
                    style = AgiTheme.typography.secondary,
                    color = colors.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::dismissNote)
                        .padding(vertical = AgiTheme.spacing.sm),
                )
            }

            if (state.loading) {
                Spacer(Modifier.height(AgiTheme.spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md)) {
                    repeat(3) { Skeleton(modifier = Modifier.fillMaxWidth(), height = 44.dp) }
                }
            } else {
                state.methods.forEach { row ->
                    MethodRowView(
                        row = row,
                        removable = state.removable,
                        onRemove = {
                            if (row.password) viewModel.removePassword() else viewModel.unlink(row)
                        },
                    )
                }

                if (!state.removable) {
                    Text(
                        text = stringResource(R.string.methods_last_note),
                        style = AgiTheme.typography.caption,
                        color = colors.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(AgiTheme.spacing.xl))

            Text(
                text = stringResource(R.string.methods_add),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.sm))

            if (state.linking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Spacer(Modifier.width(AgiTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.link_working),
                        style = AgiTheme.typography.secondary,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.height(AgiTheme.spacing.md))
            }

            Column(verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md)) {
                state.linkable.forEach { provider ->
                    SecondaryButton(
                        text = stringResource(
                            R.string.methods_link_provider,
                            stringResource(provider.titleRes),
                        ),
                        enabled = !state.linking,
                        onClick = { open(context, viewModel, provider) },
                    )
                }

                // Пароль заводится письмом, а не формой: аккаунт без пароля тем и отличается, что
                // подтвердить право на него нечем, кроме ящика. Слать некуда — кнопки нет.
                if (!state.hasPassword && state.accountEmail != null) {
                    SecondaryButton(
                        text = stringResource(R.string.methods_add_password),
                        onClick = viewModel::requestPasswordLetter,
                    )
                }

                if (state.hasPassword) {
                    SecondaryButton(
                        text = stringResource(R.string.methods_change_password),
                        onClick = {
                            // Отказ, оставшийся от прошлого действия, на форме смены пароля читался
                            // бы как отказ этой формы.
                            viewModel.dismissNote()
                            changing = true
                        },
                    )
                }
            }

            Spacer(Modifier.height(AgiTheme.spacing.xl))

            Text(
                text = stringResource(R.string.methods_letters_note),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(AgiTheme.spacing.xxl))
        }
    }
}

/** Круг к провайдеру — только системный браузер: у WebView нет его сессии, и Google в нём отказывает. */
private fun open(
    context: android.content.Context,
    viewModel: AuthMethodsViewModel,
    provider: AuthProvider,
) {
    val opened = CustomTabs.open(
        context = context,
        uri = viewModel.beginLink(provider),
        toolbarColor = LightColors.background.toArgb(),
        darkToolbarColor = DarkColors.background.toArgb(),
    )
    if (!opened) viewModel.onBrowserUnavailable()
}

@Composable
private fun MethodRowView(row: MethodRow, removable: Boolean, onRemove: () -> Unit) {
    val colors = AgiTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title.resolve(),
                style = AgiTheme.typography.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                // Привязанный вручную провайдер вправе адреса не сообщать — это не поломка.
                row.email ?: stringResource(R.string.methods_email_unknown).takeIf { !row.password },
                row.addedAt?.let { stringResource(R.string.methods_added, TimeFormat.dateTime(it)) },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                )
            }
        }

        if (removable) {
            Spacer(Modifier.width(AgiTheme.spacing.md))
            if (row.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.textTertiary,
                )
            } else {
                Text(
                    text = stringResource(
                        if (row.password) R.string.methods_remove_password
                        else R.string.methods_unlink
                    ),
                    style = AgiTheme.typography.secondary,
                    color = colors.danger,
                    modifier = Modifier.clickable(onClick = onRemove),
                )
            }
        }
    }
}
