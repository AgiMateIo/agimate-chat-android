package ru.agimate.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.outlined.BrokenImage
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import ru.agimate.mobile.R
import ru.agimate.mobile.core.realtime.RealtimeStatus
import ru.agimate.mobile.core.ui.components.AgentAvatar
import ru.agimate.mobile.core.ui.components.ImageViewer
import ru.agimate.mobile.core.ui.components.MarkdownText
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.components.ViewerImage
import ru.agimate.mobile.core.ui.format.TimeFormat
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.resolve
import com.agimate.design.AgimateTokens
import ru.agimate.mobile.core.ui.theme.AgiMotion
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.data.webchat.Attachment
import ru.agimate.mobile.data.webchat.ChatMessage
import ru.agimate.mobile.data.webchat.MessageStream
import ru.agimate.mobile.data.webchat.PendingAttachment

/**
 * Что можно сделать с сообщением и его файлами.
 *
 * Пучком, а не пятью параметрами: экран передаёт их насквозь до каждого пузыря, и врозь они
 * заняли бы половину каждой сигнатуры по дороге.
 */
@Immutable
data class MessageActions(
    val onCopy: (ChatMessage) -> Unit,
    val onShare: (ChatMessage) -> Unit,
    val onOpenFile: (Attachment) -> Unit,
    val onSaveFile: (Attachment) -> Unit,
    val onShareFile: (Attachment) -> Unit,
)

/**
 * Что открыто в просмотрщике: адрес для показа и само вложение — «сохранить» и «поделиться»
 * работают с файлом, а не с картинкой на экране.
 */
private data class OpenImage(val image: ViewerImage, val attachment: Attachment)

/** За сколько элементов до верха ленты просить следующую страницу. */
private const val LOAD_OLDER_THRESHOLD = 4

/** Проявление нового элемента ленты. Короче — и появление уже читается как рывок. */
private const val APPEAR_MS = AgiMotion.nav

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
    actions: MessageActions,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit,
) {
    val colors = AgiTheme.colors
    val listState = rememberLazyListState()

    val itemCount = state.items.size

    // Список перевёрнут: индекс 0 — самое свежее сообщение внизу, конец списка — самое старое сверху.
    //
    // Число элементов заворачивается в rememberUpdatedState, а не отдаётся ключом в remember.
    // `derivedStateOf` пересчитывается только по прочитанным State-объектам, и обычное значение,
    // захваченное замыканием, навсегда осталось бы нулём из первой композиции: «близко к началу»
    // было бы вечно истинным, и чат тянул бы историю страница за страницей на каждое сообщение.
    // Ключ у remember это тоже чинит, но пересоздаёт узел на каждое сообщение — а обновляемое
    // состояние оставляет один и всегда видит текущее число.
    val count by rememberUpdatedState(itemCount)
    val nearOldest by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.isNotEmpty() && visible.last().index >= count - LOAD_OLDER_THRESHOLD
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
    //
    // Заготовка ответа — такой же новый низ ленты, как сообщение, поэтому она тоже в ключах: без
    // этого она появлялась бы за краем экрана и весь смысл заготовки терялся.
    val newestKey = state.items.firstOrNull()?.key
    LaunchedEffect(newestKey, state.isRunning) {
        val hasBottom = newestKey != null || state.isRunning
        if (hasBottom && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // Своя отправка — исключение из правила выше: за низом ленты человек следит сам, но если он
    // только что нажал «отправить», то смотреть ему теперь надо туда, куда легло его сообщение, —
    // даже если он читал историю за десять экранов отсюда.
    //
    // Счётчик, а не флаг в состоянии: подряд отправленные сообщения должны сработать каждое, а
    // повторная отправка упавшего — нет, она ничего не двигает.
    var sent by remember { mutableIntStateOf(0) }
    LaunchedEffect(sent) {
        if (sent > 0) listState.animateScrollToItem(0)
    }

    // Просмотрщик — слой поверх экрана, а не маршрут навигации: подписанный адрес живёт 15 минут,
    // и в back stack он рано или поздно окажется протухшим.
    var viewer by remember { mutableStateOf<OpenImage?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Объединение, а не imePadding с navigationBarsPadding под ним: клавиатура
                // рисуется поверх навигационной панели, и её вставка панель уже включает.
                // Два отступа подряд давали лишнюю полосу фона между клавиатурой и полем ввода.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            ChatHeader(
                state = state,
                onBack = onBack,
                onOpenSessions = onOpenSessions,
                onNewSession = onNewSession,
                onCloseSession = onCloseSession,
            )

            // Полоска забирает высоту у ленты, и появлением встык она сдвигает всё содержимое
            // рывком. Раскрытие показывает, что подвинулось и почему.
            AnimatedVisibility(
                visible = state.realtime == RealtimeStatus.Disconnected,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
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
                        // Выравнивание к низу, а не просто отступ между элементами.
                        // reverseLayout переворачивает порядок, но не аррангемент: голый
                        // spacedBy пакует к верху, и короткая переписка висела бы под шапкой,
                        // оторванная от поля ввода. Хуже того, якорь прокрутки у перевёрнутого
                        // списка держится за низ — при появлении клавиатуры лента ехала за ней,
                        // а аррангемент тут же тянул её обратно вверх.
                        verticalArrangement = Arrangement.spacedBy(
                            AgiTheme.spacing.sm,
                            Alignment.Bottom,
                        ),
                    ) {
                        // Заготовка объявлена первой: список перевёрнут, и первый элемент — самый низ.
                        //
                        // Уходит без затухания: место она освобождает ровно под пузырь ответа, и
                        // гаснущая копия оставалась бы поверх него — вместо замены вышло бы мигание.
                        if (state.isRunning) {
                            item(key = "typing") {
                                TypingBubble(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(APPEAR_MS, easing = AgiMotion.arriveEasing),
                                        fadeOutSpec = null,
                                    ),
                                )
                            }
                        }

                        chatItems(
                            items = state.items,
                            fileUrl = fileUrl,
                            actions = actions,
                            onRetryMessage = onRetryMessage,
                            onOpenImage = { viewer = it },
                        )

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

            Composer(
                state = state,
                onInputChange = onInputChange,
                onSend = { onSend(); sent++ },
                onStop = onStop,
                onAttach = onAttach,
                onRemoveAttachment = onRemoveAttachment,
            )
        }

        viewer?.let { open ->
            ImageViewer(
                image = open.image,
                onSave = { actions.onSaveFile(open.attachment) },
                onShare = { actions.onShareFile(open.attachment) },
                onClose = { viewer = null },
                // Полоска чата осталась под слоем просмотрщика: свой ответ ему нужен свой.
                status = state.fileNotice?.text?.resolve(),
            )
        }
    }
}

private fun LazyListScope.chatItems(
    items: List<ChatItem>,
    fileUrl: (String) -> String,
    actions: MessageActions,
    onRetryMessage: (ChatMessage) -> Unit,
    onOpenImage: (OpenImage) -> Unit,
) {
    items(count = items.size, key = { items[it].key }) { index ->
        // Соседи уезжают на новые места пружиной, а не телепортом. Проявление — только самому
        // нижнему элементу: это и есть только что пришедшее сообщение. Подгруженная страница
        // истории для списка такая же вставка, и с проявлением у всех лента мигала бы на каждом
        // листании вверх. Появление отдано tween'у: пружина на коротком пути выглядит вялой.
        val appear = Modifier.animateItem(
            fadeInSpec = if (index == 0) tween(APPEAR_MS, easing = AgiMotion.arriveEasing) else null,
        )

        when (val item = items[index]) {
            is ChatItem.Bubble -> MessageBubble(
                message = item.message,
                fileUrl = fileUrl,
                actions = actions,
                onRetry = { onRetryMessage(item.message) },
                onOpenImage = onOpenImage,
                modifier = appear,
            )

            is ChatItem.ProgressGroup -> ProgressGroupRow(item, modifier = appear)

            is ChatItem.DaySeparator -> DaySeparatorRow(item.label, modifier = appear)
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
                contentDescription = stringResource(R.string.action_back),
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
                state.isRunning -> R.string.chat_status_typing
                state.closed -> R.string.chat_status_closed
                !state.agentEnabled -> R.string.chat_status_agent_disabled
                else -> null
            }
            if (subtitle != null) {
                Text(
                    text = stringResource(subtitle),
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
                    contentDescription = stringResource(R.string.action_more),
                    tint = colors.textPrimary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.chat_menu_all_sessions),
                            style = AgiTheme.typography.body,
                        )
                    },
                    onClick = { menuOpen = false; onOpenSessions() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.chat_menu_new_session),
                            style = AgiTheme.typography.body,
                        )
                    },
                    onClick = { menuOpen = false; onNewSession() },
                )
                if (!state.closed) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.chat_menu_close_session),
                                style = AgiTheme.typography.body,
                            )
                        },
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
    actions: MessageActions,
    onRetry: () -> Unit,
    onOpenImage: (OpenImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AgiTheme.colors
    val own = message.isOwn
    val isError = message.stream == MessageStream.ERROR
    val text = message.text
    val hasText = !text.isNullOrBlank()
    var menuOpen by remember { mutableStateOf(false) }

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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
    ) {
        // Box — якорь меню: оно должно выйти у пузыря, а не у края экрана.
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(background, bubbleShape(own))
                    .then(
                        if (own || isError) Modifier
                        else Modifier.border(1.dp, colors.hairline, bubbleShape(false))
                    )
                    // Долгое нажатие — только у сообщения с текстом: пузырь из одного вложения весь
                    // и есть вложение, и меню там своё, файловое.
                    .then(
                        if (hasText) Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        ) else Modifier
                    )
                    .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.sm)
                    .alpha(if (message.pending) 0.6f else 1f),
            ) {
                message.attachments.forEach { attachment ->
                    AttachmentView(
                        attachment = attachment,
                        fileUrl = fileUrl,
                        actions = actions,
                        onOpenImage = onOpenImage,
                    )
                    Spacer(Modifier.height(AgiTheme.spacing.xs))
                }

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
                            text = stringResource(R.string.chat_message_failed),
                            style = AgiTheme.typography.caption,
                            color = colors.danger,
                            modifier = Modifier.clickable(onClick = onRetry),
                        )
                    } else {
                        Text(
                            text = if (message.pending) {
                                stringResource(R.string.chat_message_sending)
                            } else {
                                TimeFormat.messageStamp(message.createdAt)
                            },
                            style = AgiTheme.typography.caption,
                            color = colors.textTertiary,
                        )
                    }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.action_share),
                            style = AgiTheme.typography.body,
                        )
                    },
                    onClick = { menuOpen = false; actions.onShare(message) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.action_copy),
                            style = AgiTheme.typography.body,
                        )
                    },
                    onClick = { menuOpen = false; actions.onCopy(message) },
                )
            }
        }
    }
}

/** Радиус — панельный из токенов; срезанный угол показывает, с чьей стороны пузырь. */
private val bubbleRadius = AgimateTokens.Radius.panel
private val bubbleTail = 4.dp

private fun bubbleShape(own: Boolean) = if (own) {
    RoundedCornerShape(bubbleRadius, bubbleRadius, bubbleTail, bubbleRadius)
} else {
    RoundedCornerShape(bubbleRadius, bubbleRadius, bubbleRadius, bubbleTail)
}

/**
 * Место под картинку.
 *
 * Ширина одна на все картинки, высота считается из пропорции и подрезается: очень длинный кадр
 * иначе занял бы экран целиком, а очень широкий выродился бы в полоску.
 */
private val IMAGE_WIDTH = 240.dp
private val IMAGE_MIN_HEIGHT = 120.dp
private val IMAGE_MAX_HEIGHT = 320.dp

/** Пока пропорция неизвестна — альбомная: так снято большинство того, что присылают. */
private const val DEFAULT_IMAGE_RATIO = 4f / 3f

/**
 * Пропорции картинок, узнанные при загрузке.
 *
 * Размеров вложения сервер не отдаёт — их нет ни в ответе, ни в хранимых `parts`, — поэтому до
 * первой загрузки высота картинки неизвестна и место под неё резервируется наугад. Дальше
 * пропорция известна, и при возврате в переписку или прокрутке назад лента уже не дёргается.
 *
 * Кеш живёт в процессе и ограничен: картинок за сеанс немного, но расти без предела он не должен.
 * Настоящее лечение — размеры в контракте; тогда этот объект исчезнет.
 */
private object ImageRatios {

    private const val MAX = 128

    private val ratios = object : LinkedHashMap<String, Float>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>) = size > MAX
    }

    operator fun get(fileId: String?): Float? =
        fileId?.let { synchronized(ratios) { ratios[it] } }

    operator fun set(fileId: String?, ratio: Float) {
        fileId ?: return
        synchronized(ratios) { ratios[fileId] = ratio }
    }
}

/**
 * Вложение в пузыре.
 *
 * Картинку открывает тап — просмотрщиком; у файла открывать нечем, и тап показывает, что с ним
 * вообще можно сделать. Долгое нажатие в обоих случаях приводит к одному и тому же меню: это
 * привычный жест, и искать его отдельно для картинок человек не должен.
 */
@Composable
private fun AttachmentView(
    attachment: Attachment,
    fileUrl: (String) -> String,
    actions: MessageActions,
    onOpenImage: (OpenImage) -> Unit,
) {
    val colors = AgiTheme.colors
    val url = attachment.url?.let(fileUrl)
    var menuOpen by remember { mutableStateOf(false) }

    // Пропорцию помним между композициями: место под картинку резервируется до загрузки, и
    // приехавшая картинка не двигает ленту. Первый раз пропорция неизвестна — тогда одна правка
    // высоты на месте, без анимации.
    var ratio by remember(attachment.fileId) {
        mutableFloatStateOf(ImageRatios[attachment.fileId] ?: DEFAULT_IMAGE_RATIO)
    }
    val reserved = (IMAGE_WIDTH / ratio).coerceIn(IMAGE_MIN_HEIGHT, IMAGE_MAX_HEIGHT)

    // Размер запроса привязан к самой большой возможной рамке, а не к текущей. Иначе первый показ
    // декодировал бы картинку под рамку, посчитанную по угаданной пропорции: узнав настоящую,
    // рамка вырастала, а декодированная под старую картинка так и оставалась меньше неё — при
    // следующем заходе та же картинка занимала рамку целиком, и это выглядело как две разные.
    val platform = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(url, platform, density) {
        ImageRequest.Builder(platform)
            .data(url)
            .size(
                width = with(density) { IMAGE_WIDTH.roundToPx() },
                height = with(density) { IMAGE_MAX_HEIGHT.roundToPx() },
            )
            .build()
    }

    Box {
        if (attachment.isImage && url != null) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                loading = {
                    Skeleton(
                        modifier = Modifier.fillMaxWidth(),
                        height = reserved,
                        shape = AgiTheme.shapes.card,
                    )
                },
                // Без своей рамки протухшая ссылка схлопывала пузырь в ноль — то же дёрганье,
                // только в обратную сторону.
                error = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BrokenImage,
                            contentDescription = stringResource(R.string.viewer_load_failed),
                            tint = colors.textTertiary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                onSuccess = { state ->
                    val measured = state.painter.intrinsicSize
                    if (measured.isSpecified && measured.width > 0f && measured.height > 0f) {
                        val actual = measured.width / measured.height
                        ImageRatios[attachment.fileId] = actual
                        ratio = actual
                    }
                },
                modifier = Modifier
                    .size(width = IMAGE_WIDTH, height = reserved)
                    .background(colors.surfaceMuted, AgiTheme.shapes.card)
                    // Адрес берётся в момент тапа: подпись живёт 15 минут, и запоминать её заранее
                    // значит открыть просмотр по протухшей ссылке.
                    .combinedClickable(
                        onClick = {
                            onOpenImage(OpenImage(ViewerImage(url, attachment.name), attachment))
                        },
                        onLongClick = { menuOpen = true },
                    ),
            )
        } else {
            Row(
                modifier = Modifier
                    .background(colors.surfaceMuted, AgiTheme.shapes.control)
                    .combinedClickable(
                        onClick = { menuOpen = true },
                        onLongClick = { menuOpen = true },
                    )
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
                    text = attachment.name ?: stringResource(R.string.chat_attachment_generic),
                    style = AgiTheme.typography.secondary,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // У картинки «открыть» — это просмотрщик по тапу, и второй раз тем же словом про
            // чужое приложение говорить незачем.
            if (!attachment.isImage) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.action_open),
                            style = AgiTheme.typography.body,
                        )
                    },
                    onClick = { menuOpen = false; actions.onOpenFile(attachment) },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.action_save),
                        style = AgiTheme.typography.body,
                    )
                },
                onClick = { menuOpen = false; actions.onSaveFile(attachment) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.action_share),
                        style = AgiTheme.typography.body,
                    )
                },
                onClick = { menuOpen = false; actions.onShareFile(attachment) },
            )
        }
    }
}

/**
 * Свёрнутые шаги работы. По тапу разворачиваются: человек должен видеть, чем агент занимался, но
 * лента не должна превращаться в лог.
 */
@Composable
private fun ProgressGroupRow(group: ChatItem.ProgressGroup, modifier: Modifier = Modifier) {
    val colors = AgiTheme.colors
    var expanded by remember(group.key) { mutableStateOf(false) }
    val last = group.lines.lastOrNull()?.text.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            // Свёрнутая строка и десяток развёрнутых различаются в высоте кратно: без анимации тап
            // подбрасывает всю ленту выше группы.
            .animateContentSize()
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
                text = if (group.lines.size > 1) {
                    val more = group.lines.size - 1
                    pluralStringResource(R.plurals.chat_progress_more, more, last, more)
                } else {
                    last
                },
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DaySeparatorRow(label: UiText, modifier: Modifier = Modifier) {
    Text(
        text = label.resolve(),
        style = AgiTheme.typography.caption,
        color = AgiTheme.colors.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AgiTheme.spacing.sm),
        textAlign = TextAlign.Center,
    )
}

/**
 * Заготовка ответа — пузырь агента с точками, стоящий там, где вырастет сам ответ.
 *
 * Ответ приходит одним куском: сервер стримит только промежуточные шаги, а готовый текст публикует
 * целиком. Без заготовки полотно возникало бы на пустом месте; с ней приход ответа читается как
 * замена уже стоящего элемента, а не как рывок.
 *
 * Только точки, без текста: чем агент занят, сказано строкой progress-группы прямо над заготовкой —
 * там же, откуда этот текст брала прежняя полоска над полем ввода.
 */
@Composable
private fun TypingBubble(modifier: Modifier = Modifier) {
    val colors = AgiTheme.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .background(colors.bubbleAgent, bubbleShape(false))
                .border(1.dp, colors.hairline, bubbleShape(false))
                .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypingDots()
        }
    }
}

/** Три точки, гаснущие по очереди: то же спокойное дыхание, что у скелетонов. */
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = AgiTheme.motion.flight,
                        delayMillis = index * 160,
                        easing = AgiTheme.motion.standard,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "typing-dot-$index",
            )
            val opacity = if (AgiTheme.reducedMotion) 0.6f else alpha
            if (index > 0) Spacer(Modifier.width(AgiTheme.spacing.xs))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(AgiTheme.colors.textTertiary.copy(alpha = opacity), AgiTheme.shapes.pill)
            )
        }
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
            text = stringResource(R.string.chat_empty_title),
            style = AgiTheme.typography.subtitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(AgiTheme.spacing.sm))
        Text(
            text = stringResource(R.string.chat_empty_description),
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

    // Пока полоска уезжает, ошибки в состоянии уже нет, и текст ей нужен последний показанный —
    // иначе схлопывание шло бы по пустой строке.
    var lastError by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(state.sendError) {
        state.sendError?.let { lastError = it }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
    ) {
        AnimatedVisibility(
            visible = state.sendError != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text = lastError?.resolve().orEmpty(),
                style = AgiTheme.typography.caption,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.dangerQuiet)
                    .padding(horizontal = AgiTheme.spacing.screen, vertical = AgiTheme.spacing.sm),
            )
        }

        // Ответ на «сохранить» и «поделиться». Отдельно от полоски ошибки отправки: у той свой
        // повод и своя судьба — она ждёт, пока человек снова возьмётся за поле ввода.
        var lastNotice by remember { mutableStateOf<FileNotice?>(null) }
        LaunchedEffect(state.fileNotice) {
            state.fileNotice?.let { lastNotice = it }
        }

        AnimatedVisibility(
            visible = state.fileNotice != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val failed = lastNotice?.failed == true
            Text(
                text = lastNotice?.text?.resolve().orEmpty(),
                style = AgiTheme.typography.caption,
                color = if (failed) colors.danger else colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (failed) colors.dangerQuiet else colors.surfaceMuted)
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
                text = blocked.resolve(),
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
                    contentDescription = stringResource(R.string.cd_attach_file),
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
                        text = stringResource(R.string.chat_input_hint),
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
                        contentDescription = stringResource(R.string.cd_stop),
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
                    contentDescription = stringResource(R.string.cd_send),
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
                attachment.error != null -> attachment.error.resolve()
                attachment.uploading -> stringResource(R.string.chat_attachment_uploading)
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
                contentDescription = stringResource(R.string.cd_remove),
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
