package ru.agimate.mobile.feature.profile

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.auth.AppSession
import ru.agimate.mobile.core.auth.SessionManager
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.core.push.PushHealth
import ru.agimate.mobile.core.push.PushSubscriptions
import ru.agimate.mobile.data.user.DeviceSessionDto
import ru.agimate.mobile.data.user.UserApi
import javax.inject.Inject

data class DeviceRow(
    val id: String,
    val label: UiText,
    val web: Boolean,
    val lastSeen: java.time.Instant?,
    val isThisDevice: Boolean,
    /**
     * Идут ли на это устройство уведомления. У своей строки — с проверкой токена ([PushHealth]),
     * у чужой судить можно только по факту подписки: чужой токен нам не с чем сверить.
     */
    val notifications: Boolean = false,
    val revoking: Boolean = false,
)

data class ProfileUiState(
    val displayName: String = "",
    val email: String? = null,
    val devices: List<DeviceRow> = emptyList(),
    val loading: Boolean = true,
    /** Своя строка: `Unknown` — транспорта нет, показывать про уведомления нечего. */
    val pushHealth: PushHealth = PushHealth.Unknown,
    val error: UiText? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userApi: UserApi,
    private val sessionManager: SessionManager,
    private val subscriptions: PushSubscriptions,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** Перерегистрацию делаем один раз на экран — см. [repairPush]. */
    private var pushRepaired = false

    init {
        observeProfile()
        loadDevices()
    }

    private fun observeProfile() {
        viewModelScope.launch {
            sessionManager.state.collect { session ->
                val profile = when (session) {
                    is AppSession.Active -> session.profile
                    is AppSession.AwaitingApproval -> session.profile
                    else -> null
                } ?: return@collect
                _state.update { it.copy(displayName = profile.displayName, email = profile.email) }
            }
        }
    }

    /** @param quiet перечитывание после действия: список уже на экране, скелетоны ни к чему. */
    fun loadDevices(quiet: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = !quiet && it.devices.isEmpty(), error = null) }
            try {
                val current = sessionManager.currentSessionId
                // Отметка до запроса: всё, что подпиской подтвердится позже, в этом ответе ещё не
                // могло оказаться, и чинить по нему такое подтверждение нельзя.
                val observedAt = System.currentTimeMillis()
                val sessions = apiCall { userApi.sessions() }.unwrap("список устройств")
                // Своя строка узнаётся по sessionId, сохранённому вместе с токенами: отдельного
                // флага «это устройство» в ответе нет.
                val health = sessions.firstOrNull { it.id == current }
                    ?.let { subscriptions.health(it.push) }
                    ?: PushHealth.Unknown
                val devices = sessions
                    .map { it.toRow(current, health) }
                    // Своё устройство наверх: его человек ищет первым.
                    .sortedByDescending { it.isThisDevice }
                _state.update { it.copy(devices = devices, loading = false, pushHealth = health) }
                repairPush(health, observedAt)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().text) }
            }
        }
    }

    /**
     * Пустой `push` и разошедшийся префикс лечатся одинаково — регистрацией заново. Молча: человек
     * про поломку не спрашивал, а починка ему нужна, а не отчёт о ней.
     *
     * Попытка одна на экран. Если сервер и после неё отдаёт своё, второй заход не поможет, а цикл
     * «перечитал — починил — перечитал» получится сам собой.
     */
    private suspend fun repairPush(health: PushHealth, observedAt: Long) {
        if (!health.fixable || pushRepaired) return
        pushRepaired = true
        if (subscriptions.repair(observedAt)) loadDevices(quiet = true)
    }

    /**
     * Отзыв чужого входа. Своё устройство отсюда не гасим: на сервере это то же самое, но локальное
     * состояние осталось бы нетронутым — для выхода есть отдельная кнопка.
     */
    fun revoke(id: String) {
        val device = _state.value.devices.firstOrNull { it.id == id } ?: return
        if (device.isThisDevice || device.revoking) return

        viewModelScope.launch {
            setRevoking(id, true)
            try {
                apiCall { userApi.revokeSession(id) }
                drop(id)
                loadDevices(quiet = true)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                when (val api = e.toApiException()) {
                    // Строки уже нет — сервер прав, а список у нас устарел.
                    is ApiException.NotFound -> {
                        drop(id)
                        loadDevices(quiet = true)
                    }
                    // Отозвали нас самих, пока мы смотрели на список: обновление токенов больше не
                    // пройдёт, и осмысленное действие одно — выйти локально.
                    is ApiException.Forbidden -> sessionManager.signOut()
                    else -> {
                        setRevoking(id, false)
                        _state.update { it.copy(error = api.text) }
                    }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }

    private fun drop(id: String) {
        _state.update { it.copy(devices = it.devices.filterNot { device -> device.id == id }) }
    }

    private fun setRevoking(id: String, value: Boolean) {
        _state.update { current ->
            current.copy(
                devices = current.devices.map { if (it.id == id) it.copy(revoking = value) else it }
            )
        }
    }

    private fun DeviceSessionDto.toRow(currentSessionId: String?, health: PushHealth): DeviceRow {
        val mine = id == currentSessionId
        return DeviceRow(
            id = id,
            label = deviceLabel?.takeIf { it.isNotBlank() }?.let(::uiText)
                ?: uiText(R.string.profile_unknown_device),
            web = client.equals("WEB", ignoreCase = true),
            lastSeen = lastSeenAt ?: createdAt,
            isThisDevice = mine,
            notifications = if (mine) health.delivering else push.isNotEmpty(),
        )
    }
}
