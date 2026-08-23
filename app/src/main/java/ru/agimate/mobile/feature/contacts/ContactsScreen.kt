package ru.agimate.mobile.feature.contacts

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.ui.components.AgentAvatar
import ru.agimate.mobile.core.ui.components.EmptyState
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.format.TimeFormat
import androidx.compose.ui.res.stringResource
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import ru.agimate.mobile.data.webchat.Contact

/**
 * Главный экран: агенты как список контактов. Кнопка создания доступна всегда, в том числе на
 * пустом состоянии — оно здесь работает онбордингом.
 */
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    onQueryChange: (String) -> Unit,
    onContactClick: (Contact) -> Unit,
    onCreateAgent: () -> Unit,
    onProfile: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
) {
    val colors = AgiTheme.colors

    // Живое событие best-effort: при сбое публикации оно теряется, и счётчик чинится следующим
    // листингом. Возврат на этот экран — как раз такой момент.
    LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
    ) {
        ContactsHeader(
            query = state.query,
            onQueryChange = onQueryChange,
            onCreateAgent = onCreateAgent,
            onProfile = onProfile,
        )

        if (state.realtime == RealtimeStatus.Disconnected) {
            ConnectionBanner()
        }

        when {
            state.loading -> ContactsSkeleton()

            state.error != null && state.contacts.isEmpty() ->
                ErrorState(message = state.error.resolve(), onRetry = onRetry)

            state.isEmpty -> EmptyState(
                title = stringResource(R.string.contacts_empty_title),
                description = stringResource(R.string.contacts_empty_description),
                action = {
                    PrimaryButton(
                        text = stringResource(R.string.contacts_create),
                        onClick = onCreateAgent,
                        modifier = Modifier.width(240.dp),
                    )
                },
            )

            state.visible.isEmpty() -> EmptyState(
                title = stringResource(R.string.contacts_not_found_title),
                description = stringResource(R.string.contacts_not_found_description),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.visible, key = { it.agentId }) { contact ->
                    ContactRow(contact = contact, onClick = { onContactClick(contact) })
                }
                item { Spacer(Modifier.height(AgiTheme.spacing.xl)) }
            }
        }
    }
}

@Composable
private fun ContactsHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onProfile: () -> Unit,
) {
    val colors = AgiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = AgiTheme.spacing.screen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AgiTheme.spacing.sm, bottom = AgiTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.contacts_title),
                style = AgiTheme.typography.title,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            HeaderAction(onClick = onCreateAgent) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.contacts_create),
                    tint = colors.textPrimary,
                )
            }
            Spacer(Modifier.width(AgiTheme.spacing.xs))
            HeaderAction(onClick = onProfile) {
                Icon(
                    imageVector = Icons.Outlined.PersonOutline,
                    contentDescription = stringResource(R.string.action_profile),
                    tint = colors.textPrimary,
                )
            }
        }

        TextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AgiTheme.spacing.sm),
            placeholder = {
                Text(
                    text = stringResource(R.string.contacts_search_hint),
                    style = AgiTheme.typography.body,
                    color = colors.textTertiary,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.textTertiary,
                )
            },
            textStyle = AgiTheme.typography.body,
            shape = AgiTheme.shapes.control,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceMuted,
                unfocusedContainerColor = colors.surfaceMuted,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
            ),
        )
    }
}

@Composable
private fun HeaderAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun ConnectionBanner() {
    val colors = AgiTheme.colors
    Text(
        text = stringResource(R.string.chat_realtime_lost),
        style = AgiTheme.typography.caption,
        color = colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceMuted)
            .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
    )
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val colors = AgiTheme.colors
    val muted = !contact.enabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(name = contact.name, dimmed = muted)

        Spacer(Modifier.width(AgiTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = AgiTheme.typography.subtitle,
                    color = if (muted) colors.textSecondary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (muted) {
                    Spacer(Modifier.width(AgiTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.contacts_agent_disabled),
                        style = AgiTheme.typography.caption,
                        color = colors.textTertiary,
                    )
                }
                Spacer(Modifier.width(AgiTheme.spacing.sm))
                Text(
                    text = TimeFormat.listStamp(contact.lastActivityAt).resolve(),
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = secondaryLine(contact),
                    style = AgiTheme.typography.secondary,
                    color = if (contact.isRunning) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (contact.unreadCount > 0) {
                    Spacer(Modifier.width(AgiTheme.spacing.sm))
                    UnreadBadge(count = contact.unreadCount)
                }
            }
        }
    }
}

/**
 * Вторая строка контакта. Composable, а не чистая функция: собирается она из переводов, а язык
 * известен только в композиции.
 */
@Composable
private fun secondaryLine(contact: Contact): String = when {
    contact.isRunning -> stringResource(R.string.chat_status_typing)
    contact.preview != null -> {
        val text = contact.preview.displayText.resolve()
        if (contact.preview.fromAgent) text else stringResource(R.string.contacts_preview_own, text)
    }

    !contact.description.isNullOrBlank() -> contact.description
    else -> stringResource(R.string.contacts_never_written)
}

@Composable
private fun UnreadBadge(count: Long) {
    val colors = AgiTheme.colors
    Box(
        modifier = Modifier
            .background(colors.accent, AgiTheme.shapes.pill)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = AgiTheme.typography.caption,
            color = if (colors.isDark) colors.onAction else colors.surface,
        )
    }
}

@Composable
private fun ContactsSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AgiTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.lg),
    ) {
        Spacer(Modifier.height(AgiTheme.spacing.sm))
        repeat(6) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Skeleton(
                    modifier = Modifier.size(44.dp),
                    height = 44.dp,
                    shape = AgiTheme.shapes.card,
                )
                Spacer(Modifier.width(AgiTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Skeleton(modifier = Modifier.fillMaxWidth(0.45f), height = 13.dp)
                    Spacer(Modifier.height(AgiTheme.spacing.sm))
                    Skeleton(modifier = Modifier.fillMaxWidth(0.8f), height = 11.dp)
                }
            }
        }
    }
}
