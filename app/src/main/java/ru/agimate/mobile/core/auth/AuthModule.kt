package ru.agimate.mobile.core.auth

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.AuthedClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    /**
     * Способы входа — единственная часть авторизации, ходящая с токеном: остальное живёт в
     * [AuthApi] на клиенте без авторизации. Провайдер здесь, а не среди api данных, потому что
     * интерфейс принадлежит этому пакету.
     */
    @Provides
    @Singleton
    fun authMethodsApi(@AuthedClient retrofit: Retrofit): AuthMethodsApi =
        retrofit.create(AuthMethodsApi::class.java)

    /** Отдельной зависимостью — чтобы в тестах окно повтора можно было прокрутить руками. */
    @Provides
    @Singleton
    fun clock(): TokenRefresher.Clock = TokenRefresher.Clock.System
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindings {

    @Binds
    @Singleton
    abstract fun tokenStore(impl: KeystoreTokenStore): TokenStore
}
