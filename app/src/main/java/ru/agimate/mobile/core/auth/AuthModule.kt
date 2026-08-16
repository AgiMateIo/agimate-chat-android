package ru.agimate.mobile.core.auth

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

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
