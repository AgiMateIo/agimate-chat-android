package ru.agimate.mobile.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.agimate.mobile.core.di.ApplicationScope
import ru.agimate.mobile.core.realtime.RealtimeClient
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.data.user.UserApi
import ru.agimate.mobile.data.user.UserProfile
import javax.inject.Inject
import javax.inject.Singleton

/** С чем приложение имеет дело прямо сейчас. */
sealed interface AppSession {
    /** Ещё не знаем — читаем хранилище и профиль. */
    data object Loading : AppSession

    data object SignedOut : AppSession

    /** Роль `GUEST`: вход прошёл, токены выданы, а в продукт аккаунт ещё не пустили. */
    data class AwaitingApproval(val profile: UserProfile) : AppSession

    data class Active(val profile: UserProfile) : AppSession

    /** Токены есть, но профиль не удалось получить — обычно нет связи. */
    data class Unavailable(val message: String) : AppSession
}

/**
 * Кто вошёл и что ему можно. Профиль перечитывается при старте и после каждого возвращения в
 * приложение: одобрение администратором может прийти в любой момент, и человек не должен
 * перезапускать приложение, чтобы его увидеть.
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val userApi: UserApi,
    private val authRepository: AuthRepository,
    private val realtime: RealtimeClient,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AppSession>(AppSession.Loading)
    val state: StateFlow<AppSession> = _state.asStateFlow()

    private val loading = Mutex()

    /** Идентификатор строки этого устройства в списке сессий. */
    val currentSessionId: String? get() = tokenStore.load()?.sessionId

    init {
        scope.launch {
            tokenStore.tokens.collect { tokens ->
                if (tokens == null) {
                    // Токенов нет — живому соединению не на чем держаться, и его токены тоже мертвы.
                    realtime.stop()
                    _state.value = AppSession.SignedOut
                } else {
                    loadProfile()
                }
            }
        }
    }

    /** Позвать при возвращении в приложение. */
    fun refresh() {
        scope.launch { loadProfile() }
    }

    suspend fun signOut() {
        authRepository.logout()
    }

    private suspend fun loadProfile() = loading.withLock {
        if (tokenStore.load() == null) {
            _state.value = AppSession.SignedOut
            return@withLock
        }
        try {
            val profile = UserProfile.from(
                apiCall { userApi.me() }.unwrap("профиль пользователя")
            )
            _state.value = if (profile.role.approved) {
                AppSession.Active(profile)
            } else {
                AppSession.AwaitingApproval(profile)
            }
        } catch (e: Throwable) {
            when (val api = e.toApiException()) {
                // Обновление уже не помогло — токены вычищены, поток хранилища доведёт до входа.
                is ApiException.Unauthorized -> _state.value = AppSession.SignedOut
                is ApiException.Offline ->
                    if (_state.value is AppSession.Loading) {
                        _state.value = AppSession.Unavailable(api.message.orEmpty())
                    }
                else -> _state.value = AppSession.Unavailable(api.message.orEmpty())
            }
        }
    }
}
