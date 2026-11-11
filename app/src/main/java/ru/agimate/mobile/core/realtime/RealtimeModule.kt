package ru.agimate.mobile.core.realtime

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Экраны видят узкий [RealtimeEventsSource], а не [RealtimeClient] с токенами, реконнектом и
 * статусом соединения.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RealtimeModule {

    @Binds
    @Singleton
    abstract fun bindRealtimeEventsSource(impl: RealtimeClient): RealtimeEventsSource
}
