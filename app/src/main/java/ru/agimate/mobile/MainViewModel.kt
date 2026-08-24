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
import ru.agimate.mobile.core.auth.ProviderLinking
import ru.agimate.mobile.core.auth.SessionManager
import ru.agimate.mobile.core.onboarding.OnboardingStore
import ru.agimate.mobile.core.push.PushChatTarget
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import javax.inject.Inject

enum class LoginPhase { Idle, WaitingForBrowser, Exchanging }

data class LoginUiState(
    val phase: LoginPhase = LoginPhase.Idle,
    val message: UiText? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val authRepository: AuthRepository,
    private val linking: ProviderLinking,
    private val onboarding: OnboardingStore,
) : ViewModel() {

    val session = sessionManager.state

    /** Рассказ о приложении уже прочитан или пропущен. */
    val onboardingSeen: StateFlow<Boolean> = onboarding.seen

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    /**
     * Переписка, которую попросили открыть тапом по уведомлению.
     *
     * Держится до тех пор, пока навигация не готова её принять: пуш приходит и когда профиль ещё
     * грузится, и когда человек вообще не вошёл, — а переход возможен только внутри продукта.
     */
    private val _pendingChat = MutableStateFlow<PushChatTarget?>(null)
    val pendingChat: StateFlow<PushChatTarget?> = _pendingChat.asStateFlow()

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
            message = uiText(R.string.login_no_browser),
        )
    }

    /**
     * Пришли из браузера. Вход и привязка возвращаются в один и тот же адрес, и различать их
     * приходится по параметру: `code` — это вход, `link_proof` — это привязка.
     *
     * `?error=` в обоих путях выглядит одинаково, поэтому решает, кто ушёл в браузер последним:
     * привязка знает, что круг за ней, а вход — что за ним.
     */
    fun onRedirect(uri: Uri?) {
        when (val redirect = AuthRedirect.parse(uri)) {
            null -> Unit
            is AuthRedirect.Failed -> {
                if (linking.awaiting) {
                    linking.failed()
                } else {
                    authRepository.abandonPendingLogin()
                    _login.value = LoginUiState(
                        phase = LoginPhase.Idle,
                        message = uiText(R.string.login_failed_retry),
                    )
                }
            }

            // Гасим доказательство сразу по возвращении, не дожидаясь действий человека: пять минут
            // его жизни отведены на дорогу от колбэка до запроса, а не на раздумья.
            is AuthRedirect.LinkProof -> linking.redeem(redirect.value, redirect.provider)

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
        // Вернулись без доказательства — значит вкладку закрыли. Молча: закрыть её человек решил
        // сам, и отчитываться ему об этом отказом незачем.
        if (linking.awaiting) linking.abandon()
        if (_login.value.phase == LoginPhase.WaitingForBrowser) {
            authRepository.abandonPendingLogin()
            _login.value = LoginUiState(
                phase = LoginPhase.Idle,
                message = uiText(R.string.login_not_finished_retry),
            )
        }
    }

    fun onPushChat(target: PushChatTarget?) {
        if (target != null) _pendingChat.value = target
    }

    fun onPendingChatHandled() {
        _pendingChat.value = null
    }

    fun finishOnboarding() = onboarding.markSeen()

    fun replayOnboarding() = onboarding.replay()

    fun dismissLoginMessage() {
        _login.value = _login.value.copy(message = null)
    }

    fun signOut() {
        // Снятие подписки на пуши делает сама сессия: порядок «сначала пуши, потом логаут» —
        // её инвариант, а не знание экрана.
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
                        // Своя формулировка: у потерянного verifier'а нет ничего, что стоило бы
                        // показать, кроме предложения начать заново.
                        is PendingLoginLost -> uiText(R.string.login_lost)
                        else -> error.toApiException().text
                    },
                )
            }
        }
    }
}
