package ru.agimate.mobile.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keystore на JVM недоступен, поэтому в тестах хранилище держится в памяти. */
class FakeTokenStore(initial: AuthTokens?) : TokenStore {

    private val state = MutableStateFlow(initial)
    var saveCount: Int = 0
        private set
    var cleared: Boolean = false
        private set

    override val tokens: StateFlow<AuthTokens?> = state

    override fun load(): AuthTokens? = state.value

    override fun save(tokens: AuthTokens) {
        saveCount++
        state.value = tokens
    }

    override fun clear() {
        cleared = true
        state.value = null
    }
}
