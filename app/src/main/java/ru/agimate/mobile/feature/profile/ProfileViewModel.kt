package ru.agimate.mobile.feature.profile

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
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.data.user.DeviceSessionDto
import ru.agimate.mobile.data.user.UserApi
import javax.inject.Inject

data class DeviceRow(
    val id: String,
    val label: String,
    val web: Boolean,
    val lastSeen: java.time.Instant?,
    val isThisDevice: Boolean,
    val revoking: Boolean = false,
)

data class ProfileUiState(
    val displayName: String = "",
    val email: String? = null,
    val devices: List<DeviceRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userApi: UserApi,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

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

    fun loadDevices() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val current = sessionManager.currentSessionId
                val devices = apiCall { userApi.sessions() }
                    .unwrap("список устройств")
                    .map { it.toRow(current) }
                    // Своё устройство наверх: его человек ищет первым.
                    .sortedByDescending { it.isThisDevice }
                _state.update { it.copy(devices = devices, loading = false) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().message) }
            }
        }
    }

    /**
     * Отдельной операции «выйти на остальных» нет — перебираем список и зовём DELETE на каждое
     * чужое устройство. Своё не отзываем: для этого есть кнопка выхода.
     */
    fun revoke(id: String) {
        val device = _state.value.devices.firstOrNull { it.id == id } ?: return
        if (device.isThisDevice || device.revoking) return

        viewModelScope.launch {
            setRevoking(id, true)
            runCatching { apiCall { userApi.revokeSession(id) } }
                .onSuccess {
                    _state.update { it.copy(devices = it.devices.filterNot { d -> d.id == id }) }
                }
                .onFailure { e ->
                    setRevoking(id, false)
                    _state.update { it.copy(error = e.toApiException().message) }
                }
        }
    }

    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }

    private fun setRevoking(id: String, value: Boolean) {
        _state.update { current ->
            current.copy(
                devices = current.devices.map { if (it.id == id) it.copy(revoking = value) else it }
            )
        }
    }

    private fun DeviceSessionDto.toRow(currentSessionId: String?) = DeviceRow(
        id = id,
        label = deviceLabel?.takeIf { it.isNotBlank() } ?: "Неизвестное устройство",
        web = client.equals("WEB", ignoreCase = true),
        lastSeen = lastSeenAt ?: createdAt,
        isThisDevice = id == currentSessionId,
    )
}
