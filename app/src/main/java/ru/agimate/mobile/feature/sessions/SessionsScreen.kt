package ru.agimate.mobile.feature.sessions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.ui.components.EmptyState
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.format.TimeFormat
import androidx.compose.ui.res.stringResource
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import androidx.compose.ui.text.AnnotatedString
import ru.agimate.mobile.core.ui.components.draftLine
import ru.agimate.mobile.data.drafts.Draft
import ru.agimate.mobile.data.webchat.ChatSession

@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    onOpen: (ChatSession) -> Unit,
    onNew: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = AgiTheme.colors
    val listState = rememberLazyListState()

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.sessions.lastIndex - 3
        }
    }
    LaunchedEffect(nearEnd, state.sessions.size) {
        if (nearEnd) onLoadMore()
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.agentName.ifBlank { stringResource(R.string.sessions_title) },
                    style = AgiTheme.typography.subtitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.sessions_subtitle),
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.error != null && state.sessions.isEmpty() ->
                    ErrorState(message = state.error.resolve(), onRetry = onRetry)

                !state.loading && state.sessions.isEmpty() -> EmptyState(
                    title = stringResource(R.string.sessions_empty_title),
                    description = stringResource(R.string.sessions_empty_description),
                )

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.sessionId }) { session ->
                        SessionRow(
                            session = session,
                            draft = state.drafts[session.sessionId],
                            onClick = { onOpen(session) },
                        )
                    }
                    item { Spacer(Modifier.height(AgiTheme.spacing.xl)) }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(AgiTheme.spacing.screen)
        ) {
            PrimaryButton(
                text = stringResource(R.string.sessions_new),
                onClick = onNew,
                busy = state.creating,
            )
        }
    }
}

@Composable
private fun SessionRow(session: ChatSession, draft: Draft?, onClick: () -> Unit) {
    val colors = AgiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.md)
            // Закрытые переписки видны, но приглушены: история в них есть, писать нельзя.
            .alpha(if (session.isClosed) 0.55f else 1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.sessions_untitled),
                style = AgiTheme.typography.subtitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AgiTheme.spacing.sm))
            Text(
                text = TimeFormat.listStamp(session.lastActivityAt ?: session.createdAt).resolve(),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Работающий агент важнее черновика: это происходит прямо сейчас, а черновик ждёт.
                text = when {
                    session.isRunning -> AnnotatedString(stringResource(R.string.chat_status_typing))
                    draft != null -> draftLine(draft.preview.resolve())
                    session.preview != null -> AnnotatedString(session.preview.displayText.resolve())
                    else -> AnnotatedString(stringResource(R.string.sessions_empty_preview))
                },
                style = AgiTheme.typography.secondary,
                color = if (session.isRunning) colors.accent else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.isClosed) {
                Spacer(Modifier.width(AgiTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.sessions_closed),
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                )
            }
            if (session.unreadCount > 0) {
                Spacer(Modifier.width(AgiTheme.spacing.sm))
                Box(
                    modifier = Modifier
                        .background(colors.accent, AgiTheme.shapes.pill)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = session.unreadCount.toString(),
                        style = AgiTheme.typography.caption,
                        color = if (colors.isDark) colors.onAction else colors.surface,
                    )
                }
            }
        }
    }
}
