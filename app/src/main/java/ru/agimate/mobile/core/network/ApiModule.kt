package ru.agimate.mobile.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import ru.agimate.mobile.core.auth.AuthMethodsApi
import ru.agimate.mobile.data.agents.AgentsApi
import ru.agimate.mobile.data.files.FilesApi
import ru.agimate.mobile.data.push.PushApi
import ru.agimate.mobile.data.user.UserApi
import ru.agimate.mobile.data.webchat.WebchatApi
import javax.inject.Singleton

/** Retrofit-интерфейсы, ходящие с авторизацией. */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    /**
     * Способы входа — единственная часть авторизации, ходящая с токеном: остальное живёт в
     * [ru.agimate.mobile.core.auth.AuthApi] на клиенте без авторизации.
     */
    @Provides
    @Singleton
    fun authMethodsApi(@AuthedClient retrofit: Retrofit): AuthMethodsApi =
        retrofit.create(AuthMethodsApi::class.java)

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
