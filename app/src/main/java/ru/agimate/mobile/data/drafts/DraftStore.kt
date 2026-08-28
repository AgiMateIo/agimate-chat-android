package ru.agimate.mobile.data.drafts

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.auth.KeystoreCipher
import ru.agimate.mobile.core.di.ApplicationScope
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.data.files.AttachmentUploader
import ru.agimate.mobile.data.files.PendingAttachment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Черновики переписок — то, что набрано, но не отправлено.
 *
 * Живёт дольше экрана намеренно. `ChatViewModel` привязан к записи маршрута и умирает при переходе
 * в соседнюю переписку; композер, лежащий в нём, умирал вместе с ним. Здесь же он общий, и экран
 * его только рисует.
 *
 * Отсюда же берётся загрузка вложения: она идёт в области приложения, а не экрана. Раньше уход из
 * переписки её отменял — файл терялся молча, и черновик, теряющий вложение при уходе, был бы
 * половиной решения.
 *
 * **Шифруется тем же способом, что токены.** Приложение не держит на устройстве ничего из
 * переписки: история всегда с сервера, файлы — в кэше и по подписанной ссылке. Черновик стал бы
 * единственным пользовательским текстом на диске, и лежать открытым ему незачем.
 */
@Singleton
class DraftStore @Inject constructor(
    @param:ApplicationContext context: Context,
    private val uploader: AttachmentUploader,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    private val _drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())
    private val exposed = _drafts.asStateFlow()

    private var loaded = false
    private var persistJob: Job? = null

    /** Все черновики, по идентификатору переписки. На него подписаны оба списка. */
    val drafts: StateFlow<Map<String, Draft>>
        get() {
            ensureLoaded()
            return exposed
        }

    fun draft(sessionId: String): Draft? {
        ensureLoaded()
        return _drafts.value[sessionId]
    }

    fun setText(sessionId: String, agentId: String?, text: String) {
        update(sessionId, agentId) { it.copy(text = text) }
    }

    /**
     * Приложить выбранное. Незагруженное отсюда же и загружается — в области приложения, чтобы уход
     * с экрана его не отменил.
     */
    fun attach(sessionId: String, agentId: String?, items: List<PendingAttachment>) {
        if (items.isEmpty()) return
        update(sessionId, agentId) { it.copy(attachments = it.attachments + items) }
        items.filter { it.fileId == null }.forEach { upload(sessionId, it) }
    }

    fun removeAttachment(sessionId: String, localId: String) {
        update(sessionId, agentId = null) { draft ->
            draft.copy(attachments = draft.attachments.filterNot { it.localId == localId })
        }
    }

    /** Вложения после проверки: ушедшие по сроку файлы убирает открывшийся экран. */
    fun replaceAttachments(sessionId: String, attachments: List<PendingAttachment>) {
        update(sessionId, agentId = null) { it.copy(attachments = attachments) }
    }

    /** Черновика больше нет: сообщение ушло, переписка закрыта, поле очищено. */
    fun clear(sessionId: String) {
        ensureLoaded()
        if (_drafts.value[sessionId] == null) return
        _drafts.update { it - sessionId }
        schedulePersist()
    }

    /** Выход из аккаунта. Черновик — текст человека, и уходит он вместе с токенами. */
    fun clear() {
        ensureLoaded()
        if (_drafts.value.isEmpty()) return
        _drafts.value = emptyMap()
        schedulePersist()
    }

    /**
     * Записать немедленно. Зовётся при остановке экрана: до `onCleared` дело доходит не всегда, а
     * набранное к этому моменту уже жалко.
     */
    fun flush() {
        persistJob?.cancel()
        scope.launch { persist() }
    }

    // ---------------------------------------------------------------- внутри

    private fun upload(sessionId: String, item: PendingAttachment) {
        scope.launch {
            uploader.upload(item)
                .onSuccess { fileId ->
                    patch(sessionId, item.localId) { it.copy(fileId = fileId, uploading = false) }
                }
                .onFailure { error ->
                    patch(sessionId, item.localId) {
                        it.copy(uploading = false, error = error.toApiException().text)
                    }
                }
        }
    }

    private fun patch(
        sessionId: String,
        localId: String,
        transform: (PendingAttachment) -> PendingAttachment,
    ) {
        update(sessionId, agentId = null) { draft ->
            draft.copy(
                attachments = draft.attachments.map {
                    if (it.localId == localId) transform(it) else it
                }
            )
        }
    }

    /**
     * Опустевший черновик не хранится: пустая строка в списке выглядела бы как забытое сообщение.
     *
     * `agentId` берётся из вызова, только когда он там есть: правки вроде «загрузка дошла» его не
     * знают, и затирать им уже записанный нельзя.
     */
    private fun update(sessionId: String, agentId: String?, transform: (Draft) -> Draft) {
        ensureLoaded()
        _drafts.update { current ->
            val before = current[sessionId] ?: Draft(sessionId = sessionId, agentId = agentId)
            val after = transform(before).copy(
                agentId = agentId ?: before.agentId,
                updatedAt = System.currentTimeMillis(),
            )
            if (after.isEmpty) current - sessionId else current + (sessionId to after)
        }
        schedulePersist()
    }

    /**
     * Запись отложенная: набор слова иначе стоил бы шифрования на каждую букву. Потерять при этом
     * можно только последние полсекунды — экран при остановке всё равно зовёт [flush].
     */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DELAY_MILLIS)
            persist()
        }
    }

    private fun persist() {
        val payload = DraftCodec.encode(_drafts.value.values)
        prefs.edit { putString(KEY_PAYLOAD, cipher.encrypt(payload)) }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true

        val encoded = prefs.getString(KEY_PAYLOAD, null) ?: return
        val raw = cipher.decrypt(encoded)
        if (raw == null) {
            // Ключ перевыпущен, файл повреждён, бэкап с чужого устройства — держать мусор незачем.
            prefs.edit { remove(KEY_PAYLOAD) }
            return
        }
        _drafts.value = DraftCodec.decode(raw)
    }

    private companion object {
        const val FILE = "agimate.drafts"
        const val KEY_PAYLOAD = "drafts"
        const val KEY_ALIAS = "agimate.drafts.key"
        const val PERSIST_DELAY_MILLIS = 500L
    }
}
