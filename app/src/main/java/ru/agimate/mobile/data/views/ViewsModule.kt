package ru.agimate.mobile.data.views

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Экран и контроллер карточек видят узкий [ToolCallViewSource], а не репозиторий целиком. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ViewsModule {

    @Binds
    @Singleton
    abstract fun bindToolCallViewSource(impl: ToolCallViewLoader): ToolCallViewSource
}
