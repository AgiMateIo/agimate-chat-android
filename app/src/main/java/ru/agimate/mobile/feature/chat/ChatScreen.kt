package ru.agimate.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.ui.components.AgentAvatar
import ru.agimate.mobile.core.ui.components.MarkdownText
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.data.webchat.Attachment
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageStream
import ru.agimate.mobile.data.webchat.PendingAttachment

/** За сколько элементов до верха ленты просить следующую страницу. */
private const val LOAD_OLDER_THRESHOLD = 4

@Composable
fun ChatScreen(
    state: ChatUiState,
    fileUrl: (String) -> String,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onReachedBottom: () -> Unit,
    onRetryMessage: (ChatMessage) -> Unit,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit,
) {
    val colors = AgiTheme.colors
    val listState = rememberLazyListState()

    val itemCount = state.items.size

    // Список перевёрнут: индекс 0 — самое свежее сообщение внизу, конец списка — самое старое сверху.
    //
    // Ключ у remember обязателен. `derivedStateOf` пересчитывается только по прочитанным
    // State-объектам, а число элементов — обычное значение, захваченное замыканием: без ключа оно
    // навсегда осталось бы нулём из первой композиции, «близко к началу» было бы вечно истинным, и
    // чат тянул бы историю страница за страницей на каждое новое сообщение.
    val nearOldest by remember(itemCount) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.isNotEmpty() && visible.last().index >= itemCount - LOAD_OLDER_THRESHOLD
        }
    }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    LaunchedEffect(nearOldest, itemCount) {
        if (nearOldest) onLoadOlder()
    }
    LaunchedEffect(atBottom, itemCount) {
        if (atBottom) onReachedBottom()
    }

    // Своё отправленное сообщение и свежий ответ должны оказаться перед глазами. Но только если
    // человек стоял внизу: тому, кто ушёл читать историю вверх, дёргать ленту нельзя.
    val newestKey = state.items.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        ChatHeader(
            state = state,
            onBack = onBack,
            onOpenSessions = onOpenSessions,
            onNewSession = onNewSession,
            onCloseSession = onCloseSession,
        )

        if (state.realtime == RealtimeStatus.Disconnected) {
            Text(
                text = "Связь потеряна — пробуем восстановить",
                style = AgiTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceMuted)
                    .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> ChatSkeleton()

                state.items.isEmpty() -> EmptyChatHint(agentName = state.agentName)

                else -> LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = AgiTheme.spacing.screen,
                        vertical = AgiTheme.spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.sm),
                ) {
                    chatItems(state.items, fileUrl, onRetryMessage)

                    if (state.loadingOlder) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AgiTheme.spacing.md),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }

        }

        // Полоска говорит только о том, чем агент занят прямо сейчас. Что он вообще работает, уже
        // сказано подписью в шапке и кнопкой «стоп», и заглушка здесь была третьим повтором одного
        // и того же — а заодно дёргала ленту, появляясь и исчезая на пустом месте.
        val progress = state.liveProgress?.takeIf { it.isNotBlank() }
        if (state.isRunning && progress != null) {
            RunningStrip(progress = progress)
        }

        Composer(
            state = state,
            onInputChange = onInputChange,
            onSend = onSend,
            onStop = onStop,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
        )
    }
}

private fun LazyListScope.chatItems(
    items: List<ChatItem>,
    fileUrl: (String) -> String,
    onRetryMessage: (ChatMessage) -> Unit,
) {
    items(count = items.size, key = { items[it].key }) { index ->
        when (val item = items[index]) {
            is ChatItem.Bubble -> MessageBubble(
                message = item.message,
                fileUrl = fileUrl,
                onRetry = { onRetryMessage(item.message) },
            )

            is ChatItem.ProgressGroup -> ProgressGroupRow(item)

            is ChatItem.DaySeparator -> DaySeparatorRow(item.label)
        }
    }
}

@Composable
private fun ChatHeader(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit,
) {
    val colors = AgiTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
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
                contentDescription = "Назад",
                tint = colors.textPrimary,
            )
        }

        AgentAvatar(name = state.agentName, size = 36.dp, dimmed = !state.agentEnabled)

        Spacer(Modifier.width(AgiTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.agentName,
                style = AgiTheme.typography.subtitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                state.isRunning -> "печатает…"
                state.closed -> "переписка закрыта"
                !state.agentEnabled -> "агент выключен"
                else -> null
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AgiTheme.typography.caption,
                    color = if (state.isRunning) colors.accent else colors.textTertiary,
                )
            }
        }

        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Ещё",
                    tint = colors.textPrimary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Все переписки", style = AgiTheme.typography.body) },
                    onClick = { menuOpen = false; onOpenSessions() },
                )
                DropdownMenuItem(
                    text = { Text("Новая переписка", style = AgiTheme.typography.body) },
                    onClick = { menuOpen = false; onNewSession() },
                )
                if (!state.closed) {
                    DropdownMenuItem(
                        text = { Text("Закрыть переписку", style = AgiTheme.typography.body) },
                        onClick = { menuOpen = false; onCloseSession() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    fileUrl: (String) -> String,
    onRetry: () -> Unit,
) {
    val colors = AgiTheme.colors
    val own = message.isOwn
    val isError = message.stream == MessageStream.ERROR

    val background = when {
        isError -> colors.dangerQuiet
        own -> colors.bubbleOwn
        else -> colors.bubbleAgent
    }
    val textColor = when {
        isError -> colors.danger
        own -> colors.bubbleOwnText
        else -> colors.bubbleAgentText
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(background, bubbleShape(own))
                .then(
                    if (own || isError) Modifier
                    else Modifier.border(1.dp, colors.hairline, bubbleShape(false))
                )
                .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.sm)
                .alpha(if (message.pending) 0.6f else 1f),
        ) {
            message.attachments.forEach { attachment ->
                AttachmentView(attachment = attachment, fileUrl = fileUrl)
                Spacer(Modifier.height(AgiTheme.spacing.xs))
            }

            val text = message.text
            if (!text.isNullOrBlank()) {
                if (own || isError) {
                    Text(text = text, style = AgiTheme.typography.body, color = textColor)
                } else {
                    // Ответ агента — markdown: списки, жирный, код, ссылки, таблицы.
                    MarkdownText(content = text, textColor = textColor, modifier = Modifier)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.failed) {
                    Text(
                        text = "не отправлено · повторить",
                        style = AgiTheme.typography.caption,
                        color = colors.danger,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                } else {
                    Text(
                        text = if (message.pending) "отправляется…" else TimeFormat.messageStamp(message.createdAt),
                        style = AgiTheme.typography.caption,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

private fun bubbleShape(own: Boolean) = if (own) {
    RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
} else {
    RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
}

@Composable
private fun AttachmentView(attachment: Attachment, fileUrl: (String) -> String) {
    val colors = AgiTheme.colors
    val url = attachment.url?.let(fileUrl)

    if (attachment.isImage && url != null) {
        AsyncImage(
            model = url,
            contentDescription = attachment.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .heightIn(max = 320.dp)
                .background(colors.surfaceMuted, AgiTheme.shapes.card),
        )
    } else {
        Row(
            modifier = Modifier
                .background(colors.surfaceMuted, AgiTheme.shapes.control)
                .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AgiTheme.spacing.sm))
            Text(
                text = attachment.name ?: "Вложение",
                style = AgiTheme.typography.secondary,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Свёрнутые шаги работы. По тапу разворачиваются: человек должен видеть, чем агент занимался, но
 * лента не должна превращаться в лог.
 */
@Composable
private fun ProgressGroupRow(group: ChatItem.ProgressGroup) {
    val colors = AgiTheme.colors
    var expanded by remember(group.key) { mutableStateOf(false) }
    val last = group.lines.lastOrNull()?.text.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = AgiTheme.spacing.xs),
    ) {
        if (expanded) {
            group.lines.forEach { line ->
                Text(
                    text = "· ${line.text.orEmpty()}",
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        } else {
            Text(
                text = if (group.lines.size > 1) "$last · ещё ${group.lines.size - 1}" else last,
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DaySeparatorRow(label: String) {
    Text(
        text = label,
        style = AgiTheme.typography.caption,
        color = AgiTheme.colors.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgiTheme.spacing.sm),
        textAlign = TextAlign.Center,
    )
}

/** Что агент делает прямо сейчас. Без шага не показывается — см. место вызова. */
@Composable
private fun RunningStrip(progress: String, modifier: Modifier = Modifier) {
    val colors = AgiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.accent,
        )
        Spacer(Modifier.width(AgiTheme.spacing.sm))
        Text(
            text = progress,
            style = AgiTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyChatHint(agentName: String) {
    val colors = AgiTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AgiTheme.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AgentAvatar(name = agentName, size = 64.dp)
        Spacer(Modifier.height(AgiTheme.spacing.lg))
        Text(
            text = "Напишите первое сообщение",
            style = AgiTheme.typography.subtitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(AgiTheme.spacing.sm))
        Text(
            text = "Обычным языком — как человеку. Можно приложить фото или документ.",
            style = AgiTheme.typography.secondary,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    val colors = AgiTheme.colors
    val blocked = state.composerBlockedReason

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
    ) {
        if (state.sendError != null) {
            Text(
                text = state.sendError,
                style = AgiTheme.typography.caption,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.dangerQuiet)
                    .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
            )
        }

        if (state.attachments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.xs),
            ) {
                state.attachments.forEach { attachment ->
                    PendingAttachmentRow(
                        attachment = attachment,
                        onRemove = { onRemoveAttachment(attachment.localId) },
                    )
                }
            }
        }

        if (blocked != null) {
            Text(
                text = blocked,
                style = AgiTheme.typography.secondary,
                color = colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AgiTheme.spacing.lg),
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AgiTheme.spacing.sm,
                    end = AgiTheme.spacing.sm,
                    top = AgiTheme.spacing.xs,
                    bottom = AgiTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "Прикрепить файл",
                    tint = colors.textSecondary,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(colors.surfaceMuted, AgiTheme.shapes.control)
                    .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.md),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.input.isEmpty()) {
                    Text(
                        text = "Сообщение",
                        style = AgiTheme.typography.body,
                        color = colors.textTertiary,
                    )
                }
                BasicTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    textStyle = AgiTheme.typography.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
            }

            Spacer(Modifier.width(AgiTheme.spacing.xs))

            // Кнопка «стоп» соседствует с отправкой, а не заменяет её: поле ввода не блокируется,
            // пока агент отвечает, и отправить второе сообщение можно прямо сейчас.
            if (state.isRunning) {
                CircleAction(
                    background = colors.surfaceMuted,
                    onClick = onStop,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Stop,
                        contentDescription = "Остановить",
                        tint = colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(AgiTheme.spacing.xs))
            }

            CircleAction(
                background = if (state.canSend) colors.action else colors.surfaceMuted,
                enabled = state.canSend,
                onClick = onSend,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Отправить",
                    tint = if (state.canSend) colors.onAction else colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CircleAction(
    background: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(background, AgiTheme.shapes.pill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun PendingAttachmentRow(attachment: PendingAttachment, onRemove: () -> Unit) {
    val colors = AgiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceMuted, AgiTheme.shapes.control)
            .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = AgiTheme.typography.secondary,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                attachment.error != null -> attachment.error
                attachment.uploading -> "загружается…"
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = AgiTheme.typography.caption,
                    color = if (attachment.error != null) colors.danger else colors.textTertiary,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Убрать",
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ChatSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AgiTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
    ) {
        repeat(5) { index ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (index % 2 == 0) Arrangement.Start else Arrangement.End,
            ) {
                Skeleton(
                    modifier = Modifier.width(if (index % 2 == 0) 220.dp else 160.dp),
                    height = 44.dp,
                    shape = AgiTheme.shapes.bubble,
                )
            }
        }
    }
}
