package ru.agimate.mobile.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import ru.agimate.mobile.core.network.AuthedClient
import ru.agimate.mobile.data.agents.AgentsApi
import ru.agimate.mobile.data.files.FilesApi
import ru.agimate.mobile.data.push.PushApi
import ru.agimate.mobile.data.user.UserApi
import ru.agimate.mobile.data.webchat.WebchatApi
import javax.inject.Singleton

/**
 * Retrofit-интерфейсы этого слоя, ходящие с авторизацией.
 *
 * Модуль лежит в `data`, а не рядом с клиентом в `core/network`: собирает он именно эти
 * интерфейсы, и от того, где он лежит, зависит направление зависимости между слоями. Отсюда
 * `core/network` — уже только про транспорт: конверт, заголовки, обновление токена, — и о том,
 * какие ресурсы существуют, ничего не знает.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun userApi(@AuthedClient retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides
    @Singleton
    fun webchatApi(@AuthedClient retrofit: Retrofit): WebchatApi =
        retrofit.create(WebchatApi::class.java)

    @Provides
    @Singleton
    fun agentsApi(@AuthedClient retrofit: Retrofit): AgentsApi =
        retrofit.create(AgentsApi::class.java)

    @Provides
    @Singleton
    fun filesApi(@AuthedClient retrofit: Retrofit): FilesApi = retrofit.create(FilesApi::class.java)

    @Provides
    @Singleton
    fun pushApi(@AuthedClient retrofit: Retrofit): PushApi = retrofit.create(PushApi::class.java)
}
