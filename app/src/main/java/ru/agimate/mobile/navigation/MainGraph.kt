package ru.agimate.mobile.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.agimate.mobile.core.push.PushChatTarget
import ru.agimate.mobile.core.push.RequestNotificationPermission
import ru.agimate.mobile.core.share.rememberPhotoCapture
import ru.agimate.mobile.core.share.rememberSaveGate
import ru.agimate.mobile.feature.authmethods.AuthMethodsScreen
import ru.agimate.mobile.feature.chat.ChatEffect
import ru.agimate.mobile.feature.chat.ChatScreen
import ru.agimate.mobile.feature.chat.ChatViewModel
import ru.agimate.mobile.feature.chat.MessageActions
import ru.agimate.mobile.data.files.StoredFile
import ru.agimate.mobile.feature.contacts.ContactsScreen
import ru.agimate.mobile.feature.contacts.ContactsViewModel
import ru.agimate.mobile.feature.createagent.CreateAgentScreen
import ru.agimate.mobile.feature.createagent.CreateAgentViewModel
import ru.agimate.mobile.feature.files.FileActions
import ru.agimate.mobile.feature.files.FilesEffect
import ru.agimate.mobile.feature.files.FilesScope
import ru.agimate.mobile.feature.files.FilesScreen
import ru.agimate.mobile.feature.files.FilesViewModel
import ru.agimate.mobile.feature.profile.ProfileScreen
import ru.agimate.mobile.feature.profile.ProfileViewModel
import ru.agimate.mobile.feature.settings.SettingsScreen
import ru.agimate.mobile.feature.sessions.SessionsScreen
import ru.agimate.mobile.feature.sessions.SessionsViewModel

/** Маршруты внутри продукта. Собраны в одном месте, чтобы строки не разъезжались по экранам. */
object Routes {
    const val CONTACTS = "contacts"
    const val CREATE_AGENT = "create-agent"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val AUTH_METHODS = "auth-methods"
    const val FILES = "files"

    const val SESSIONS = "sessions/{agentId}?agentName={agentName}&agentEnabled={agentEnabled}"
    fun sessions(agentId: String, agentName: String, agentEnabled: Boolean = true) =
        "sessions/$agentId?agentName=${Uri.encode(agentName)}&agentEnabled=${flag(agentEnabled)}"

    const val CHAT =
        "chat/{sessionId}?agentId={agentId}&agentName={agentName}&agentEnabled={agentEnabled}"

    fun chat(
        sessionId: String,
        agentId: String?,
        agentName: String,
        agentEnabled: Boolean = true,
    ) = "chat/$sessionId?agentId=${agentId.orEmpty()}" +
        "&agentName=${Uri.encode(agentName)}&agentEnabled=${flag(agentEnabled)}"

    /** Булев параметр маршрута — строкой: NavType.BoolType не умеет быть nullable. */
    private fun flag(value: Boolean) = if (value) "1" else "0"
}

private fun optionalArg(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@Composable
fun MainGraph(
    onSignOut: () -> Unit,
    pendingChat: PushChatTarget? = null,
    onPendingChatHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    RequestNotificationPermission()

    // Тап по уведомлению открывает переписку. `launchSingleTop` — чтобы второй пуш той же
    // переписки не укладывал в стек её копию.
    LaunchedEffect(pendingChat) {
        val target = pendingChat ?: return@LaunchedEffect
        navController.navigate(
            Routes.chat(target.sessionId, target.agentId, target.agentName)
        ) { launchSingleTop = true }
        onPendingChatHandled()
    }

    NavHost(
        navController = navController,
        startDestination = Routes.CONTACTS,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.CONTACTS) {
            val viewModel: ContactsViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            ContactsScreen(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onContactClick = { contact ->
                    // Тап по контакту — это «написать». Открываем ту переписку, из которой взято
                    // превью, а если агенту ещё не писали, заводим первую прямо здесь.
                    viewModel.openChat(contact) { sessionId ->
                        navController.navigate(
                            Routes.chat(sessionId, contact.agentId, contact.name, contact.enabled)
                        )
                    }
                },
                onCreateAgent = { navController.navigate(Routes.CREATE_AGENT) },
                onProfile = { navController.navigate(Routes.PROFILE) },
                onRetry = { viewModel.load() },
                onResume = { viewModel.load(refresh = true) },
            )
        }

        composable(
            route = Routes.SESSIONS,
            arguments = listOf(
                navArgument("agentId") { type = NavType.StringType },
                optionalArg("agentName"),
                optionalArg("agentEnabled"),
            ),
        ) { entry ->
            val viewModel: SessionsViewModel = hiltViewModel()
            val agentEnabled = entry.arguments?.getString("agentEnabled") != "0"
            val state by viewModel.state.collectAsStateWithLifecycle()

            SessionsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onOpen = { session ->
                    navController.navigate(
                        Routes.chat(
                            session.sessionId, viewModel.agentId, state.agentName, agentEnabled
                        )
                    )
                },
                onNew = {
                    viewModel.startNew { sessionId ->
                        navController.navigate(
                            Routes.chat(sessionId, viewModel.agentId, state.agentName, agentEnabled)
                        )
                    }
                },
                onLoadMore = viewModel::loadMore,
                onRetry = viewModel::load,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                optionalArg("agentId"),
                optionalArg("agentName"),
                optionalArg("agentEnabled"),
            ),
        ) { entry ->
            val viewModel: ChatViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val agentId = entry.arguments?.getString("agentId")?.takeIf { it.isNotBlank() }
            val agentEnabled = entry.arguments?.getString("agentEnabled") != "0"

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris) }

            // Снимок камерой — такой же источник вложения, как диск: дальше по конвейеру едет
            // обычный content-адрес, и загрузке всё равно, откуда он взялся.
            val camera = rememberPhotoCapture(
                onFailed = viewModel::onPhotoFailed,
                onPhoto = { uri -> viewModel.addAttachments(listOf(uri)) },
            )

            // Диалог «поделиться» и открытие файла чужим приложением запускает экран: ViewModel
            // собирает интент, но `startActivity` нужен контекст, и держать его во ViewModel
            // значит держать там же утёкшую Activity.
            val context = LocalContext.current
            LaunchedEffect(viewModel) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is ChatEffect.Launch -> context.startActivity(effect.intent)
                    }
                }
            }

            // До Android 10 сохранение требует разрешения, и спросить его может только экран.
            val save = rememberSaveGate(onDenied = viewModel::onSaveDenied)
            val actions = remember(viewModel, save) {
                MessageActions(
                    onCopy = viewModel::copyMessage,
                    onShare = viewModel::shareMessage,
                    onOpenFile = viewModel::openAttachment,
                    onSaveFile = { attachment -> save { viewModel.saveAttachment(attachment) } },
                    onShareFile = viewModel::shareAttachment,
                    onImageFailed = viewModel::onImageFailed,
                )
            }

            // Файлы открываются поверх переписки, а не отдельным маршрутом: назад надо вернуть не
            // строку, а выбранный файл, и маршруту пришлось бы возить его сериализованным.
            //
            // Поводов два, и области у них разные. Из меню переписки смотрят её вложения — там
            // фильтр по этой переписке, и тап открывает файл. Со скрепки выбирают, что приложить, —
            // а приложить можно любой свой файл, и фильтра там нет.
            var files by rememberSaveable { mutableStateOf<FilesMode?>(null) }
            if (files != null) {
                BackHandler { files = null }
                Files(
                    scope = if (files == FilesMode.Pick) {
                        FilesScope.All
                    } else {
                        FilesScope.Session(viewModel.sessionId)
                    },
                    onBack = { files = null },
                    onPick = if (files == FilesMode.Pick) {
                        { file -> viewModel.attachStored(file); files = null }
                    } else {
                        null
                    },
                )
                return@composable
            }

            ChatScreen(
                state = state,
                fileUrl = viewModel::fileUrl,
                onBack = { navController.popBackStack() },
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onAttachFile = { picker.launch(arrayOf("*/*")) },
                onTakePhoto = camera,
                onRemoveAttachment = viewModel::removeAttachment,
                onLoadOlder = viewModel::loadOlder,
                onReachedBottom = viewModel::onReachedBottom,
                onRetryMessage = viewModel::retry,
                actions = actions,
                onOpenSessions = {
                    if (agentId != null) {
                        navController.navigate(
                            Routes.sessions(agentId, state.agentName, agentEnabled)
                        )
                    }
                },
                onNewSession = {
                    viewModel.startNewSession { sessionId ->
                        navController.navigate(
                            Routes.chat(sessionId, agentId, state.agentName, agentEnabled)
                        ) {
                            popUpTo(Routes.CHAT) { inclusive = true }
                        }
                    }
                },
                onCloseSession = {
                    viewModel.closeSession { navController.popBackStack() }
                },
                onOpenFiles = { files = FilesMode.Browse },
                onPickStored = { files = FilesMode.Pick },
            )
        }

        composable(Routes.CREATE_AGENT) {
            val viewModel: CreateAgentViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            CreateAgentScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSelect = viewModel::select,
                onBackToGallery = viewModel::backToGallery,
                onNameChange = viewModel::onNameChange,
                onInstructionsChange = viewModel::onInstructionsChange,
                onToggleInstructions = viewModel::toggleInstructions,
                onCreate = {
                    // Кнопка завершения ведёт сразу в чат, а не обратно в список.
                    viewModel.create { created ->
                        navController.navigate(
                            Routes.chat(created.sessionId, created.agentId, created.agentName)
                        ) {
                            popUpTo(Routes.CREATE_AGENT) { inclusive = true }
                        }
                    }
                },
                onRetry = viewModel::load,
            )
        }

        composable(Routes.PROFILE) {
            val viewModel: ProfileViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            ProfileScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onAuthMethods = { navController.navigate(Routes.AUTH_METHODS) },
                onFiles = { navController.navigate(Routes.FILES) },
                onRevoke = viewModel::revoke,
                onSignOut = onSignOut,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.AUTH_METHODS) {
            AuthMethodsScreen(onBack = { navController.popBackStack() })
        }

        // Из профиля — все файлы: сужать их здесь не до чего.
        composable(Routes.FILES) {
            Files(scope = FilesScope.All, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Экран файлов со всей обвязкой: запуск чужого приложения, разрешение на память, действия строки.
 * Общий для двух мест, откуда он открывается, — иначе обвязку пришлось бы писать дважды.
 *
 * Область передаётся вызовом, а не берётся из аргументов маршрута: внутри переписки экран
 * открывается по двум разным поводам, а `hiltViewModel` отдаёт им один и тот же [FilesViewModel] —
 * он привязан к записи маршрута, а не к месту вызова.
 */
@Composable
private fun Files(
    scope: FilesScope,
    onBack: () -> Unit,
    onPick: ((StoredFile) -> Unit)? = null,
) {
    val viewModel: FilesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, scope) { viewModel.scope(scope) }

    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FilesEffect.Launch -> context.startActivity(effect.intent)
            }
        }
    }

    val save = rememberSaveGate(onDenied = viewModel::onSaveDenied)
    val actions = remember(viewModel, save) {
        FileActions(
            onOpen = viewModel::open,
            onSave = { file -> save { viewModel.save(file) } },
            onShare = viewModel::share,
            onDelete = viewModel::delete,
        )
    }

    FilesScreen(
        state = state,
        fileUrl = viewModel::fileUrl,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::load,
        actions = actions,
        onImageFailed = viewModel::onImageFailed,
        onPick = onPick,
    )
}

/** Зачем открыт список файлов: приложить к сообщению или просто посмотреть. */
private enum class FilesMode { Browse, Pick }
