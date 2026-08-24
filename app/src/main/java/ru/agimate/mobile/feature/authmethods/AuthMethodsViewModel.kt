package ru.agimate.mobile.feature.authmethods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.agimate.mobile.R
import ru.agimate.mobile.core.auth.AppSession
import ru.agimate.mobile.core.auth.AuthMethodsApi
import ru.agimate.mobile.core.auth.AuthProvider
import ru.agimate.mobile.core.auth.AuthRepository
import ru.agimate.mobile.core.auth.ChangePasswordRequest
import ru.agimate.mobile.core.auth.LinkOutcome
import ru.agimate.mobile.core.auth.LinkState
import ru.agimate.mobile.core.auth.LoginMethodDto
import ru.agimate.mobile.core.auth.PasswordRules
import ru.agimate.mobile.core.auth.ProviderLinking
import ru.agimate.mobile.core.auth.SessionManager
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import java.time.Instant
import javax.inject.Inject

/**
 * Одна дверь в аккаунт.
 *
 * @param code написание провайдера, как его прислал сервер: им же он и отвязывается. Держим строку,
 *             а не только разобранный [provider], чтобы отвязать можно было и провайдера, которого
 *             это приложение ещё не знает.
 */
data class MethodRow(
    val password: Boolean,
    val provider: AuthProvider?,
    val code: String?,
    val title: UiText,
    val email: String?,
    val addedAt: Instant?,
    val busy: Boolean = false,
)

data class AuthMethodsUiState(
    val methods: List<MethodRow> = emptyList(),
    val loading: Boolean = true,
    /** Адрес аккаунта: на него уходит письмо, заводящее пароль. `null` — слать некуда. */
    val accountEmail: String? = null,
    val error: UiText? = null,
    /** Сказанное вслух после действия: привязали, отвязали, письмо ушло. */
    val note: UiText? = null,
    val linking: Boolean = false,
    val changing: Boolean = false,
) {
    val hasPassword: Boolean get() = methods.any { it.password }

    /** Последний способ входа не отвязывается: кнопку гасим заранее, а не ловим отказ сервера. */
    val removable: Boolean get() = methods.size > 1

    /** Провайдеры, которых у аккаунта ещё нет: один провайдер — одна дверь. */
    val linkable: List<AuthProvider>
        get() = AuthProvider.offered.filter { entry -> methods.none { it.provider == entry } }
}

/**
 * Экран способов входа: перечислить, привязать ещё один, убрать лишний.
 *
 * Привязка живёт не здесь, а в [ProviderLinking]: доказательство возвращается из браузера в
 * Activity, к тому моменту экран может быть и пересоздан. Здесь остаётся показать исход и
 * перечитать список.
 */
@HiltViewModel
class AuthMethodsViewModel @Inject constructor(
    private val api: AuthMethodsApi,
    private val auth: AuthRepository,
    private val linking: ProviderLinking,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthMethodsUiState())
    val state: StateFlow<AuthMethodsUiState> = _state.asStateFlow()

    init {
        observeAccount()
        observeLinking()
        load()
    }

    private fun observeAccount() {
        viewModelScope.launch {
            sessionManager.state.collect { session ->
                val profile = when (session) {
                    is AppSession.Active -> session.profile
                    is AppSession.AwaitingApproval -> session.profile
                    else -> null
                } ?: return@collect
                _state.update { it.copy(accountEmail = profile.email) }
            }
        }
    }

    private fun observeLinking() {
        viewModelScope.launch {
            linking.state.collect { state ->
                when (state) {
                    LinkState.Idle -> _state.update { it.copy(linking = false) }

                    is LinkState.Working -> _state.update {
                        it.copy(linking = true, note = null, error = null)
                    }

                    is LinkState.Failed -> {
                        _state.update { it.copy(linking = false, error = state.text) }
                        linking.consume()
                    }

                    is LinkState.Done -> {
                        val title = state.provider
                            ?.let { uiText(it.titleRes) }
                            ?: uiText(R.string.provider_other)
                        _state.update {
                            it.copy(
                                linking = false,
                                note = if (state.outcome.success) message(state.outcome, title) else null,
                                error = if (state.outcome.success) null else message(state.outcome, title),
                            )
                        }
                        linking.consume()
                        // Отказ списка не меняет, но и не портит: перечитать дешевле, чем гадать.
                        load(quiet = true)
                    }
                }
            }
        }
    }

    private fun message(outcome: LinkOutcome, provider: UiText): UiText = when (outcome) {
        LinkOutcome.LINKED -> uiText(R.string.link_linked, provider)
        LinkOutcome.ALREADY_YOURS -> uiText(R.string.link_already_yours, provider)
        LinkOutcome.TAKEN -> uiText(R.string.link_taken, provider)
        LinkOutcome.PROVIDER_OCCUPIED -> uiText(R.string.link_occupied, provider)
        LinkOutcome.UNKNOWN -> uiText(R.string.link_unknown_outcome)
    }

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = !quiet && it.methods.isEmpty(), error = null) }
            try {
                val methods = apiCall { api.methods() }.unwrap("способы входа")
                _state.update { it.copy(methods = methods.map(::toRow), loading = false) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toApiException().text) }
            }
        }
    }

    /** Круг к провайдеру. Адрес возвращаем наружу: браузер запускает экран, а не ViewModel. */
    fun beginLink(provider: AuthProvider) = linking.begin(provider)

    fun onBrowserUnavailable() {
        linking.abandon()
        _state.update { it.copy(error = uiText(R.string.login_no_browser)) }
    }

    fun unlink(row: MethodRow) {
        val code = row.code ?: return
        if (row.busy || !_state.value.removable) return

        viewModelScope.launch {
            busy(row, true)
            act(
                request = { apiCall { api.unlinkProvider(code.uppercase()) } },
                note = uiText(R.string.methods_unlinked, row.title),
                onFailure = { busy(row, false) },
            )
        }
    }

    fun removePassword() {
        val row = _state.value.methods.firstOrNull { it.password } ?: return
        if (row.busy || !_state.value.removable) return

        viewModelScope.launch {
            busy(row, true)
            act(
                request = { apiCall { api.removePassword() } },
                note = uiText(R.string.methods_password_removed),
                onFailure = { busy(row, false) },
            )
        }
    }

    /**
     * «Добавить пароль» — то же письмо, что и «забыли пароль»: одна операция, две точки входа.
     * Пароль задаётся по ссылке из письма, а не здесь: аккаунт без пароля тем и отличается, что
     * подтвердить право на него нечем, кроме ящика.
     */
    fun requestPasswordLetter() {
        val email = _state.value.accountEmail ?: return

        viewModelScope.launch {
            _state.update { it.copy(note = null, error = null) }
            auth.requestPasswordLetter(email)
                .onSuccess {
                    _state.update { it.copy(note = uiText(R.string.methods_password_letter, email)) }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.toApiException().text) }
                }
        }
    }

    /**
     * Смена пароля гасит все остальные сессии — эта переживает. Проверка длины здесь же: сервер
     * считает байты, и форма обязана считать их так же, иначе отказ придёт с сервера на том, что
     * выглядело допустимым.
     */
    fun changePassword(current: String, new: String, onDone: () -> Unit) {
        val problem = PasswordRules.problem(new)
        if (problem != null) {
            _state.update { it.copy(error = problem, note = null) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(changing = true, error = null, note = null) }
            try {
                apiCall { api.changePassword(ChangePasswordRequest(current, new)) }
                _state.update {
                    it.copy(changing = false, note = uiText(R.string.methods_password_changed))
                }
                load(quiet = true)
                onDone()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(changing = false, error = e.toApiException().text) }
            }
        }
    }

    fun dismissNote() = _state.update { it.copy(note = null, error = null) }

    private suspend fun act(
        request: suspend () -> Unit,
        note: UiText,
        onFailure: () -> Unit,
    ) {
        try {
            request()
            _state.update { it.copy(note = note) }
            load(quiet = true)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            onFailure()
            _state.update { it.copy(error = e.toApiException().text) }
        }
    }

    private fun busy(row: MethodRow, value: Boolean) = _state.update { current ->
        current.copy(
            methods = current.methods.map {
                if (it.password == row.password && it.code == row.code) it.copy(busy = value) else it
            }
        )
    }

    private fun toRow(dto: LoginMethodDto): MethodRow {
        val password = dto.provider == null || dto.kind.equals("PASSWORD", ignoreCase = true)
        val provider = AuthProvider.of(dto.provider)
        return MethodRow(
            password = password,
            provider = provider,
            code = dto.provider,
            // Название известного провайдера — из ресурсов: «Яндекс» и «Yandex» это одна компания
            // на двух языках. Незнакомого — от сервера: другого имени у приложения для него нет.
            title = when {
                password -> uiText(R.string.methods_password)
                provider != null -> uiText(provider.titleRes)
                else -> uiText(dto.title ?: dto.provider.orEmpty())
            },
            email = dto.email?.takeIf { it.isNotBlank() },
            addedAt = dto.addedAt,
        )
    }
}
