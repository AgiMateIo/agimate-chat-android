package ru.agimate.mobile.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.core.auth.AuthRepository
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.ui.text.UiText
import javax.inject.Inject

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean get() = !busy && email.isNotBlank() && password.isNotEmpty()
}

/**
 * Форма, которая заканчивается письмом, а не входом: заявка на регистрацию и просьба о пароле.
 *
 * @param sent письмо отправлено — насколько об этом вообще можно судить. Ответ сервера одинаков и
 *             когда адрес свободен, и когда занят, и когда писем на него за час было пять: иначе
 *             форма стала бы проверялкой, кто здесь зарегистрирован.
 */
data class LetterUiState(
    val email: String = "",
    val displayName: String = "",
    val busy: Boolean = false,
    val sent: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean get() = !busy && email.isNotBlank()
}

/**
 * Вход по паролю и два письма рядом с ним.
 *
 * Отдельно от `MainViewModel` намеренно: тот ведёт круг через браузер, который начинается в
 * Activity и возвращается туда же. У формы такого устройства нет — она живёт и умирает на своём
 * экране, и тащить её состояние через корневую развилку незачем.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _signIn = MutableStateFlow(SignInUiState())
    val signIn: StateFlow<SignInUiState> = _signIn.asStateFlow()

    private val _letter = MutableStateFlow(LetterUiState())
    val letter: StateFlow<LetterUiState> = _letter.asStateFlow()

    /**
     * Умеет ли эта установка слать письма. Узнаётся единственным способом — по 503 в ответ на
     * первую же просьбу: отдельного «а есть ли у вас почта» у сервера нет. До первого отказа
     * считаем, что умеет; после — прячем всё, что упирается в письмо, до конца жизни процесса.
     */
    private val _mailAvailable = MutableStateFlow(true)
    val mailAvailable: StateFlow<Boolean> = _mailAvailable.asStateFlow()

    fun onEmail(value: String) = _signIn.update { it.copy(email = value, error = null) }

    fun onPassword(value: String) = _signIn.update { it.copy(password = value, error = null) }

    /**
     * Дальше состояние ведёт `SessionManager`: он подхватит появившиеся токены, и экран сменится
     * сам. Поэтому успех здесь ничего не показывает — показывать уже некому.
     */
    fun submitSignIn() {
        val form = _signIn.value
        if (!form.canSubmit) return

        viewModelScope.launch {
            _signIn.update { it.copy(busy = true, error = null) }
            auth.signIn(form.email, form.password)
                .onFailure { error ->
                    _signIn.update { it.copy(busy = false, error = error.toApiException().text) }
                }
        }
    }

    fun onLetterEmail(value: String) = _letter.update { it.copy(email = value, error = null) }

    fun onDisplayName(value: String) = _letter.update { it.copy(displayName = value, error = null) }

    /** Открыть форму письма: адрес переносим со входа, чтобы не набирать его дважды. */
    fun startLetter() {
        _letter.value = LetterUiState(email = _signIn.value.email)
    }

    fun submitRegistration() = sendLetter {
        auth.register(_letter.value.email, _letter.value.displayName)
    }

    fun resendConfirmation() = sendLetter { auth.resendConfirmation(_letter.value.email) }

    fun submitPasswordLetter() = sendLetter { auth.requestPasswordLetter(_letter.value.email) }

    private fun sendLetter(request: suspend () -> Result<Unit>) {
        if (!_letter.value.canSubmit) return

        viewModelScope.launch {
            _letter.update { it.copy(busy = true, error = null) }
            request()
                .onSuccess { _letter.update { it.copy(busy = false, sent = true) } }
                .onFailure { error ->
                    val failure = error.toApiException()
                    if (failure is ApiException.Server && failure.code == MAIL_NOT_CONFIGURED) {
                        _mailAvailable.value = false
                    }
                    _letter.update { it.copy(busy = false, error = failure.text) }
                }
        }
    }

    private companion object {
        /** Почта на установке не настроена: письма не будет, и флоу, требующие письма, лишние. */
        const val MAIL_NOT_CONFIGURED = 503
    }
}
