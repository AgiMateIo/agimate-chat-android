package ru.agimate.mobile.core.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PushBindings {

    @Binds
    @Singleton
    abstract fun transport(impl: PushClient): PushTransport

    @Binds
    @Singleton
    abstract fun registrations(impl: PrefsPushRegistrationLog): PushRegistrationLog
}
