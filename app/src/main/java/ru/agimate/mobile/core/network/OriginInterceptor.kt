package ru.agimate.mobile.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retrofit требует baseUrl на этапе сборки, а адрес бэкенда в dev меняется в рантайме. Поэтому
 * baseUrl — заглушка, а настоящие схема/хост/порт подставляются здесь на каждом запросе.
 */
@Singleton
class OriginInterceptor @Inject constructor(
    private val origins: OriginProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = origins.current.toHttpUrl()
        val request = chain.request()
        val rewritten = request.url.newBuilder()
            .scheme(origin.scheme)
            .host(origin.host)
            .port(origin.port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }

    companion object {
        /** Только чтобы Retrofit было что распарсить: реальный адрес подставляет интерсептор. */
        const val PLACEHOLDER_BASE_URL = "http://origin.invalid/"
    }
}
