package ru.agimate.mobile.feature.views

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.EmptyState
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import ru.agimate.mobile.core.views.ViewCsp
import ru.agimate.mobile.core.views.AgentViewHost
import ru.agimate.mobile.core.views.viewHostContext
import ru.agimate.mobile.data.views.AgentView

/**
 * Вью агента: список доступных страниц и одна открытая.
 *
 * Открытая вью — не отдельный маршрут, а состояние этого же экрана: возврат к списку не должен
 * стоить нового запроса, а системный «назад» из списка ведёт дальше по навигации. Страница при
 * этом живёт в песочнице хоста и на экран отдаётся только её HTML.
 */
@Composable
fun ViewsScreen(
    state: ViewsUiState,
    agentName: String,
    onBack: () -> Unit,
    onOpen: (AgentView) -> Unit,
    onCloseOpened: () -> Unit,
    onReload: () -> Unit,
    onRetry: () -> Unit,
    onCallTool: suspend (AgentView, String, JsonObject) -> JsonObject,
    onRendererGone: () -> Unit,
) {
    val opened = state.opened

    // Пока страница открыта, «назад» значит «к списку»; из списка системный «назад» просто
    // уводит с маршрута — перехватывать там нечего.
    BackHandler(enabled = opened != null) { onCloseOpened() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
    ) {
        if (opened == null) {
            ViewsHeader(agentName = agentName, onBack = onBack)
            ViewsList(state = state, onOpen = onOpen, onRetry = onRetry)
        } else {
            OpenedHeader(view = opened.view, onBack = onCloseOpened, onReload = onReload)
            OpenedBody(
                opened = opened,
                onReload = onReload,
                onCallTool = { name, arguments -> onCallTool(opened.view, name, arguments) },
                onRendererGone = onRendererGone,
                // Панель навигации и клавиатура отнимают высоту у страницы, а не у шапки:
                // так WebView не уезжает под системные элементы, а поле ввода страницы
                // остаётся видно.
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun ViewsHeader(agentName: String, onBack: () -> Unit) {
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
                tint = AgiTheme.colors.textPrimary,
            )
        }
        Spacer(Modifier.width(AgiTheme.spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.views_title),
                style = AgiTheme.typography.subtitle,
                color = AgiTheme.colors.textPrimary,
            )
            if (agentName.isNotBlank()) {
                Text(
                    text = agentName,
                    style = AgiTheme.typography.caption,
                    color = AgiTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ViewsList(
    state: ViewsUiState,
    onOpen: (AgentView) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.views_hint),
            style = AgiTheme.typography.secondary,
            color = AgiTheme.colors.textSecondary,
            modifier = Modifier.padding(
                start = AgiTheme.spacing.screen,
                end = AgiTheme.spacing.screen,
                bottom = AgiTheme.spacing.sm,
            ),
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                // Скелетон вместо спиннера: форма карточек уже известна, и место под них
                // не должно прыгать после загрузки.
                state.loading && state.views.isEmpty() -> Column(
                    modifier = Modifier.padding(
                        start = AgiTheme.spacing.screen,
                        end = AgiTheme.spacing.screen,
                        top = AgiTheme.spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
                ) {
                    repeat(3) {
                        Skeleton(
                            modifier = Modifier.fillMaxWidth(),
                            height = 64.dp,
                            shape = AgiTheme.shapes.card,
                        )
                    }
                }

                state.error != null && state.views.isEmpty() -> ErrorState(
                    message = state.error.resolve(),
                    onRetry = onRetry,
                )

                state.views.isEmpty() -> EmptyState(
                    title = stringResource(R.string.views_title),
                    description = stringResource(R.string.views_empty),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AgiTheme.spacing.screen,
                        end = AgiTheme.spacing.screen,
                        top = AgiTheme.spacing.sm,
                        bottom = AgiTheme.spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
                ) {
                    items(state.views, key = { "${it.connectionId}:${it.uri}" }) { view ->
                        ViewCard(view = view, onOpen = { onOpen(view) })
                    }
                }
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * Строка вью: название и значок «открыть». Нажатие — открыть: строка целиком и есть цель,
 * значок лишь называет действие. Значок `OpenInNew` говорит, что откроется новая страница.
 */
@Composable
private fun ViewCard(view: AgentView, onOpen: () -> Unit) {
    val colors = AgiTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.hairline, AgiTheme.shapes.card)
            .clickable(onClick = onOpen)
            .padding(start = AgiTheme.spacing.md, end = AgiTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = view.title,
            style = AgiTheme.typography.subtitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpen) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(R.string.views_open),
                tint = colors.accent,
            )
        }
    }
}

@Composable
private fun OpenedHeader(view: AgentView, onBack: () -> Unit, onReload: () -> Unit) {
    val colors = AgiTheme.colors

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
                contentDescription = stringResource(R.string.views_back),
                tint = colors.textPrimary,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = view.title,
                style = AgiTheme.typography.subtitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = view.uri,
                style = AgiTheme.typography.mono,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Перезагрузку не прячем в меню: страница живёт в песочнице и может не догрузиться,
        // а «Обновить» — единственный способ пересоздать её, не выходя к списку.
        Row(
            modifier = Modifier
                .clickable(onClick = onReload)
                .padding(horizontal = AgiTheme.spacing.sm, vertical = AgiTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AgiTheme.spacing.xs))
            Text(
                text = stringResource(R.string.views_reload),
                style = AgiTheme.typography.caption,
                color = colors.accent,
                maxLines = 1,
            )
        }
    }
}

/**
 * Тело открытой вью: загрузка, ошибка или сама страница.
 *
 * Хост ключуется ревизией: `AndroidView.factory` захватывает содержимое один раз на композицию,
 * поэтому без нового ключа «Обновить» не пересоздал бы ни WebView, ни канал.
 */
@Composable
private fun OpenedBody(
    opened: OpenedView,
    onReload: () -> Unit,
    onCallTool: suspend (String, JsonObject) -> JsonObject,
    onRendererGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Локаль страницы берётся из конфигурации, которую Compose сам перечитывает при смене:
    // неявное чтение из контекста вернуло бы устаревшее значение.
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val openLink = rememberViewLinkOpener()
    val colors = AgiTheme.colors
    val hostContext = viewHostContext(
        colors = colors,
        locale = locale,
    )
    // Нет системного WebView — показывать нечего и пересоздавать нечего. Проверка дешёвая и
    // делается один раз на экран.
    val webViewSupported = remember(context) { WebView.getCurrentWebViewPackage() != null }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            opened.error != null -> ErrorState(
                message = opened.error.resolve(),
                onRetry = onReload,
                retryText = stringResource(R.string.views_reload),
            )

            opened.loading -> PageLoading()

            else -> {
                val content = opened.content
                when {
                    content == null -> ErrorState(
                        message = stringResource(R.string.views_load_error),
                        onRetry = onReload,
                        retryText = stringResource(R.string.views_reload),
                    )

                    !webViewSupported -> EmptyState(
                        title = stringResource(R.string.views_title),
                        description = stringResource(R.string.views_unsupported),
                    )

                    else -> key(opened.revision) {
                        AgentViewHost(
                            content = content,
                            policy = ViewCsp.policy(opened.csp),
                            hostContext = hostContext,
                            background = colors.background,
                            onCallTool = onCallTool,
                            onOpenLink = openLink,
                            onRendererGone = onRendererGone,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Skeleton(
            modifier = Modifier.fillMaxWidth(0.5f),
            height = 16.dp,
        )
        Spacer(Modifier.height(AgiTheme.spacing.md))
        Skeleton(
            modifier = Modifier.fillMaxWidth(0.7f),
            height = 12.dp,
        )
        Spacer(Modifier.height(AgiTheme.spacing.xl))
        Text(
            text = stringResource(R.string.views_loading),
            style = AgiTheme.typography.secondary,
            color = AgiTheme.colors.textSecondary,
        )
    }
}
