package ru.agimate.mobile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.auth.AuthRedirect
import ru.agimate.mobile.core.auth.AuthRepository
import ru.agimate.mobile.core.auth.PendingLoginLost
import ru.agimate.mobile.core.auth.SessionManager
import ru.agimate.mobile.core.network.OriginProvider
import ru.agimate.mobile.core.network.toApiException
import javax.inject.Inject

enum class LoginPhase { Idle, WaitingForBrowser, Exchanging }

data class LoginUiState(
    val phase: LoginPhase = LoginPhase.Idle,
    val message: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val authRepository: AuthRepository,
    private val origins: OriginProvider,
) : ViewModel() {

    val session = sessionManager.state

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    val origin: StateFlow<String> = origins.origin

    val originEditable: Boolean = BuildConfig.ALLOW_ORIGIN_OVERRIDE

    /**
     * Адрес входа для Custom Tabs. Возвращаем наружу, а не открываем сами: запуск браузера — дело
     * Activity, у ViewModel нет и не должно быть Context'а.
     */
    fun beginLogin(provider: AuthProvider): Uri {
        _login.value = LoginUiState(phase = LoginPhase.WaitingForBrowser)
        return authRepository.authorizationUri(provider)
    }

    fun onBrowserUnavailable() {
        authRepository.abandonPendingLogin()
        _login.value = LoginUiState(
            phase = LoginPhase.Idle,
            message = "Не нашли браузер, в котором открыть вход",
        )
    }

    /** Пришли из браузера. Разбираем: код — меняем, ошибка — показываем «войти ещё раз». */
    fun onRedirect(uri: Uri?) {
        when (val redirect = AuthRedirect.parse(uri)) {
            null -> Unit
            is AuthRedirect.Failed -> {
                authRepository.abandonPendingLogin()
                _login.value = LoginUiState(
                    phase = LoginPhase.Idle,
                    message = "Вход не удался. Попробуйте ещё раз.",
                )
            }

            is AuthRedirect.Code -> exchange(redirect.value)
        }
    }

    /**
     * Возврат в приложение. Профиль перечитывается всегда — одобрение может прийти в любой момент.
     *
     * Отдельно чиним «зависший вход»: проверка домена может не сработать, редирект уйдёт в браузер,
     * и кода приложение не увидит. Тогда человек просто закрывает вкладку и возвращается — и должен
     * увидеть экран входа с возможностью повторить, а не вечный спиннер.
     */
    fun onAppResumed() {
        sessionManager.refresh()
        if (_login.value.phase == LoginPhase.WaitingForBrowser) {
            authRepository.abandonPendingLogin()
            _login.value = LoginUiState(
                phase = LoginPhase.Idle,
                message = "Вход не завершён. Попробуйте ещё раз.",
            )
        }
    }

    fun dismissLoginMessage() {
        _login.value = _login.value.copy(message = null)
    }

    fun changeOrigin(value: String) {
        if (origins.override(value)) {
            _login.value = _login.value.copy(message = null)
            sessionManager.refresh()
        } else {
            _login.value = _login.value.copy(message = "Адрес не похож на правильный")
        }
    }

    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }

    private fun exchange(code: String) {
        _login.value = LoginUiState(phase = LoginPhase.Exchanging)
        viewModelScope.launch {
            val result = authRepository.exchangeCode(code)
            result.onSuccess {
                // Дальше состояние ведёт SessionManager: он подхватит появившиеся токены.
                _login.value = LoginUiState()
            }.onFailure { error ->
                _login.value = LoginUiState(
                    phase = LoginPhase.Idle,
                    message = when (error) {
                        is PendingLoginLost -> error.message
                        else -> error.toApiException().message
                    } ?: "Вход не удался",
                )
            }
        }
    }
}
