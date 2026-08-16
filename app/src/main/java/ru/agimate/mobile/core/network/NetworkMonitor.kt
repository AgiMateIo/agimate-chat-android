package ru.agimate.mobile.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Когда сеть появилась снова.
 *
 * Событие, а не состояние: повтор нужен именно на переход. Ждать полный интервал backoff, когда
 * связь уже вернулась, значит держать чат немым на ровном месте — а интервал к тому времени успевает
 * дорасти до минуты.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _becameAvailable = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val becameAvailable: SharedFlow<Unit> = _becameAvailable.asSharedFlow()

    init {
        // Регистрация без снятия: монитор живёт столько же, сколько процесс. Отписываться негде и
        // незачем — система снимает колбэк вместе с процессом.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            _becameAvailable.tryEmit(Unit)
                        }
                    }
                )
        }
    }
}
