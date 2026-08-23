package ru.agimate.mobile.feature.files

import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.R
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.OriginProvider
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.share.FileNotice
import ru.agimate.mobile.core.share.FileStore
import ru.agimate.mobile.core.share.RemoteFile
import ru.agimate.mobile.core.share.SavedTo
import ru.agimate.mobile.core.share.Sharing
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import ru.agimate.mobile.data.files.FilesRepository
import ru.agimate.mobile.data.files.StoredFile
import javax.inject.Inject

data class FilesUiState(
    val files: List<StoredFile> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: UiText? = null,
    val notice: FileNotice? = null,
    /** Список ограничен одним агентом: пустой экран тогда объясняется иначе. */
    val agentScoped: Boolean = false,
)

/** Разовое действие экрана: запуск чужого приложения. `startActivity` нужен контекст, а не ViewModel. */
sealed interface FilesEffect {
    data class Launch(val intent: Intent) : FilesEffect
}

/**
 * Файлы пользователя.
 *
 * Фильтр по агенту берётся из маршрута, в котором экран открыт: в переписке аргумент `agentId` есть,
 * в профиле его нет — и список получается общим. Отдельного флага «режим» поэтому не нужно.
 *
 * Важное следствие фильтра: `agentId` на сервере значит «кто произвёл файл», а у загруженного
 * руками вложения он пуст. В переписке видно то, что сделал агент, а не то, что человек туда
 * отправил.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: FilesRepository,
    private val store: FileStore,
    private val sharing: Sharing,
    private val origins: OriginProvider,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val agentId: String? = savedState.get<String>("agentId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(FilesUiState(agentScoped = agentId != null))
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    private val _effects = Channel<FilesEffect>(Channel.BUFFERED)
    val effects: Flow<FilesEffect> = _effects.receiveAsFlow()

    private var nextPage = 0
    private var searchJob: Job? = null
    private var fileJob: Job? = null
    private var noticeJob: Job? = null

    init {
        load()
    }

    fun fileUrl(url: String): String = origins.fileUrl(url)

    fun load() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            fetch(page = 0)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.endReached) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            fetch(page = nextPage)
        }
    }

    /**
     * Поиск идёт на сервер, а не по загруженной странице: файлов может быть больше, чем показано.
     * Пауза перед запросом — чтобы набор слова не стоил десяти запросов; предыдущий поиск при этом
     * отменяется, иначе ответы обгонят друг друга и в списке останется тот, что пришёл последним.
     */
    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            _state.update { it.copy(loading = true, error = null) }
            fetch(page = 0)
        }
    }

    /**
     * Удаление раньше срока. Строка уходит сразу: на сервере это простановка срока «сейчас», и
     * возвращать её на место, чтобы через мгновение убрать снова, — только мигание.
     */
    fun delete(file: StoredFile) {
        val before = _state.value.files
        _state.update { it.copy(files = it.files.filterNot { row -> row.id == file.id }) }
        viewModelScope.launch {
            try {
                repository.delete(file.id)
                notice(uiText(R.string.files_deleted))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // Файла и так нет — это успех, а не отказ.
                if (e is ApiException.NotFound) return@launch
                _state.update { it.copy(files = before) }
                notice(e.toApiException().text, failed = true)
            }
        }
    }

    fun open(file: StoredFile) = withFile(file, R.string.file_opening) { remote ->
        handOff(sharing.openFile(store.cache(remote), remote.mime))
        null
    }

    fun share(file: StoredFile) = withFile(file, R.string.file_preparing) { remote ->
        handOff(sharing.shareFile(store.cache(remote), remote.mime))
        null
    }

    fun save(file: StoredFile) = withFile(file, R.string.file_saving) { remote ->
        uiText(
            when (store.save(remote)) {
                SavedTo.GALLERY -> R.string.file_saved_gallery
                SavedTo.DOWNLOADS -> R.string.file_saved_downloads
            }
        )
    }

    /** Разрешения на память не дали. Промолчать нельзя: нажатие осталось бы без ответа. */
    fun onSaveDenied() = notice(uiText(R.string.file_save_denied), failed = true)

    private suspend fun fetch(page: Int) {
        try {
            val result = repository.files(agentId, _state.value.query, page)
            nextPage = page + 1
            _state.update {
                it.copy(
                    files = if (page == 0) result.items else it.files + result.items,
                    loading = false,
                    loadingMore = false,
                    endReached = result.isLast,
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _state.update {
                it.copy(loading = false, loadingMore = false, error = e.toApiException().text)
            }
        }
    }

    /**
     * Обвязка действий с файлом: всем троим сначала нужно его скачать, и делается это не мгновенно.
     * Адрес берётся в момент действия — подпись живёт минуты, и запомненная давно уже не откроется.
     */
    private fun withFile(
        file: StoredFile,
        @StringRes progress: Int,
        block: suspend (RemoteFile) -> UiText?,
    ) {
        val address = file.url?.takeIf { it.isNotBlank() }?.let(origins::fileUrl)
        if (address == null) {
            notice(uiText(R.string.error_file_gone), failed = true)
            return
        }
        if (fileJob?.isActive == true) return

        val remote = RemoteFile(id = file.id, url = address, name = file.name, mime = file.mime)
        fileJob = viewModelScope.launch {
            working(progress)
            try {
                val done = block(remote)
                if (done == null) clearNotice() else notice(done)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                notice(e.toApiException().text, failed = true)
            }
        }
    }

    private fun handOff(intent: Intent) {
        _effects.trySend(FilesEffect.Launch(intent))
    }

    private fun notice(text: UiText, failed: Boolean = false) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = FileNotice(text, failed)) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MILLIS)
            _state.update { it.copy(notice = null) }
        }
    }

    /** Строка о том, что происходит прямо сейчас: она и есть признак работы, гаснуть ей нельзя. */
    private fun working(@StringRes text: Int) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = FileNotice(uiText(text))) }
    }

    private fun clearNotice() {
        noticeJob?.cancel()
        _state.update { it.copy(notice = null) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val NOTICE_MILLIS = 2_500L
    }
}
