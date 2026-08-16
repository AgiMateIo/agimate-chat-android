package ru.agimate.mobile.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import ru.agimate.mobile.BuildConfig
import ru.agimate.mobile.core.auth.AuthApi
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Клиент без авторизации: обмен кода, обновление, логаут. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

/** Клиент с `Authorization` и обновлением по 401 — всё остальное API. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthedClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = ApiJson

    @Provides
    @Singleton
    fun loggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        // В релизе не логируется ничего. В отладке — заголовки: с вычеркнутым `Authorization` они
        // безопасны и говорят больше, чем строка запроса, а `BODY` тащил бы в logcat текст
        // переписки и тела токенов.
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }

    @Provides
    @Singleton
    @PlainClient
    fun plainClient(
        retry: RetryInterceptor,
        origin: OriginInterceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        // Ретраи снаружи всего: каждая попытка заново проходит подстановку origin и авторизацию.
        .addInterceptor(retry)
        .addInterceptor(origin)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @AuthedClient
    fun authedClient(
        retry: RetryInterceptor,
        origin: OriginInterceptor,
        auth: AuthInterceptor,
        authenticator: TokenAuthenticator,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(retry)
        .addInterceptor(origin)
        .addInterceptor(auth)
        .authenticator(authenticator)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        // Отправка сообщения синхронная: маршрутизация происходит внутри запроса, поэтому ответа
        // можно ждать заметно дольше обычного. Загрузка файла — до 50 МБ.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @PlainClient
    fun plainRetrofit(@PlainClient client: OkHttpClient, json: Json): Retrofit =
        retrofit(client, json)

    @Provides
    @Singleton
    @AuthedClient
    fun authedRetrofit(@AuthedClient client: OkHttpClient, json: Json): Retrofit =
        retrofit(client, json)

    @Provides
    @Singleton
    fun authApi(@PlainClient retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    private fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(OriginInterceptor.PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
