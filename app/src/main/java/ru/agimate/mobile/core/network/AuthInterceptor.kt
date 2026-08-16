package ru.agimate.mobile.core.network

import okhttp3.Interceptor
import okhttp3.Response
import ru.agimate.mobile.core.auth.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `Authorization: Bearer <accessToken>` к обоим сервисам — токен один и тот же.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val store: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = store.load()?.accessToken ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }
}
