package ru.agimate.mobile.feature.files

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.EmptyState
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import ru.agimate.mobile.data.files.StoredFile
import java.time.Duration
import java.time.Instant

/** Что можно сделать со строкой файла. Собраны в одном месте: их четыре, и едут они насквозь. */
data class FileActions(
    val onOpen: (StoredFile) -> Unit,
    val onSave: (StoredFile) -> Unit,
    val onShare: (StoredFile) -> Unit,
    val onDelete: (StoredFile) -> Unit,
)

/**
 * Файлы пользователя — списком.
 *
 * Один экран на два повода. Из переписки он выбирает вложение: тап прикрепляет файл к сообщению, и
 * загружать при этом нечего — файл уже на сервере. Из профиля выбирать не во что, и тап открывает
 * файл чужим приложением.
 */
@Composable
fun FilesScreen(
    state: FilesUiState,
    fileUrl: (String) -> String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    actions: FileActions,
    /** Превью не нарисовалось: скорее всего протухла подпись, и за ссылкой надо сходить заново. */
    onImageFailed: (StoredFile) -> Unit,
    /** Прикрепить файл к сообщению. `null` — экран открыт не из переписки, прикреплять некуда. */
    onPick: ((StoredFile) -> Unit)? = null,
) {
    val colors = AgiTheme.colors
    val listState = rememberLazyListState()

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.files.lastIndex - 3
        }
    }
    LaunchedEffect(nearEnd, state.files.size) {
        if (nearEnd) onLoadMore()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
    ) {
        FilesHeader(query = state.query, onBack = onBack, onQueryChange = onQueryChange)

        AnimatedVisibility(
            visible = state.notice != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val failed = state.notice?.failed == true
            Text(
                text = state.notice?.text?.resolve().orEmpty(),
                style = AgiTheme.typography.caption,
                color = if (failed) colors.danger else colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (failed) colors.dangerQuiet else colors.surfaceMuted)
                    .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.error != null && state.files.isEmpty() ->
                    ErrorState(message = state.error.resolve(), onRetry = onRetry)

                !state.loading && state.files.isEmpty() && state.query.isNotBlank() -> EmptyState(
                    title = stringResource(R.string.files_not_found_title),
                    description = stringResource(R.string.files_not_found_description),
                )

                !state.loading && state.files.isEmpty() -> EmptyState(
                    title = stringResource(R.string.files_empty_title),
                    description = stringResource(
                        if (state.sessionScoped) R.string.files_empty_session_description
                        else R.string.files_empty_description
                    ),
                )

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.files, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            fileUrl = fileUrl,
                            actions = actions,
                            onImageFailed = { onImageFailed(file) },
                            onClick = {
                                if (onPick != null) onPick(file) else actions.onOpen(file)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(AgiTheme.spacing.xl)) }
                }
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun FilesHeader(query: String, onBack: () -> Unit, onQueryChange: (String) -> Unit) {
    val colors = AgiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = AgiTheme.spacing.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AgiTheme.spacing.sm),
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
                text = stringResource(R.string.files_title),
                style = AgiTheme.typography.subtitle,
                color = colors.textPrimary,
            )
        }

        TextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AgiTheme.spacing.xs),
            placeholder = {
                Text(
                    text = stringResource(R.string.files_search_hint),
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
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
            ),
        )

        // Предупреждение вместо загадки: поиск идёт по имени, а у фото из мессенджера и у
        // нарисованной агентом картинки имени нет вовсе — из выдачи они пропадают целиком.
        if (query.isNotBlank()) {
            Text(
                text = stringResource(R.string.files_search_note),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(
                    horizontal = AgiTheme.spacing.md,
                    vertical = AgiTheme.spacing.xs,
                ),
            )
        }
    }
}

@Composable
private fun FileRow(
    file: StoredFile,
    fileUrl: (String) -> String,
    actions: FileActions,
    onImageFailed: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = AgiTheme.colors
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.surfaceMuted, AgiTheme.shapes.control),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            // Превью ложится поверх значка: не загрузилось — под ним осталась честная иконка.
            // Адрес берётся здесь же, при показе: подпись живёт минуты.
            val url = file.url?.takeIf { file.isImage && it.isNotBlank() }?.let(fileUrl)
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { onImageFailed() },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Transparent, AgiTheme.shapes.control),
                )
            }
        }

        Spacer(Modifier.width(AgiTheme.spacing.md))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = file.name ?: stringResource(R.string.files_unnamed),
                style = AgiTheme.typography.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Размер форматирует система: единицы измерения — тоже перевод.
                    text = Formatter.formatShortFileSize(context, file.size) + " · " +
                        TimeFormat.listStamp(file.createdAt).resolve(),
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                )
                if (file.expiresSoon()) {
                    Spacer(Modifier.width(AgiTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.files_expires_soon),
                        style = AgiTheme.typography.caption,
                        color = colors.danger,
                    )
                }
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
                    contentDescription = stringResource(R.string.action_more),
                    tint = colors.textSecondary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuAction(R.string.action_open) { menuOpen = false; actions.onOpen(file) }
                MenuAction(R.string.action_save) { menuOpen = false; actions.onSave(file) }
                MenuAction(R.string.action_share) { menuOpen = false; actions.onShare(file) }
                MenuAction(R.string.action_delete) { menuOpen = false; actions.onDelete(file) }
            }
        }
    }
}

@Composable
private fun MenuAction(textRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = stringResource(textRes), style = AgiTheme.typography.body) },
        onClick = onClick,
    )
}

/**
 * Файлы уходят по сроку сами. Молча пропавший файл выглядит как потеря, поэтому у доживающих
 * последний день срок написан.
 */
private fun StoredFile.expiresSoon(): Boolean {
    val deadline = expiresAt ?: return false
    return Duration.between(Instant.now(), deadline) < Duration.ofDays(1)
}
