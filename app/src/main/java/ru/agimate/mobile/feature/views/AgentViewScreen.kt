package ru.agimate.mobile.feature.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import ru.agimate.mobile.core.views.AgentViewHost
import ru.agimate.mobile.core.views.ViewCallData
import ru.agimate.mobile.core.views.ViewCsp
import ru.agimate.mobile.core.views.viewHostContext

/**
 * Одна вью на весь экран — тот же [AgentViewHost], что в карточке.
 *
 * `size-changed` здесь не применяется: страница и так занимает весь экран. Системный «назад»
 * возвращает в чат; карточка под ним остаётся раскрытой, потому что живёт в своей ViewModel.
 */
@Composable
fun AgentViewScreen(
    state: AgentViewUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCallTool: suspend (String, JsonObject) -> JsonObject,
    onRendererGone: () -> Unit,
) {
    val colors = AgiTheme.colors
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val hostContext = viewHostContext(colors, locale)
    val openLink = rememberViewLinkOpener()

    LaunchedEffect(state.close) {
        if (state.close) onBack()
    }

    Column(modifier = Modifier.fillMaxSize().backdrop()) {
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
                text = state.call?.name ?: stringResource(R.string.views_title),
                style = AgiTheme.typography.subtitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.views_reload),
                    tint = colors.textPrimary,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            val content = state.content
            val call = state.call
            when {
                state.error != null -> ErrorState(
                    message = state.error.resolve(),
                    onRetry = onRetry,
                    retryText = stringResource(R.string.views_reload),
                )

                content == null || call == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }

                else -> key(state.revision) {
                    AgentViewHost(
                        content = content.html,
                        policy = ViewCsp.policy(content.externalDomains),
                        hostContext = hostContext,
                        background = colors.background,
                        onCallTool = onCallTool,
                        onOpenLink = openLink,
                        onRendererGone = onRendererGone,
                        modifier = Modifier.fillMaxSize(),
                        toolCall = ViewCallData(call.input, call.result),
                    )
                }
            }
        }
    }
}
