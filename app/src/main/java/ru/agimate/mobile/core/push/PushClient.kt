package ru.agimate.mobile.core.push

import android.app.Application
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.agimate.mobile.BuildConfig
import ru.rustore.sdk.pushclient.common.logger.DefaultLogger
import ru.rustore.sdk.universalpush.RuStoreUniversalPushClient
import ru.rustore.sdk.universalpush.listener.OnMessageReceivedListener
import ru.rustore.sdk.universalpush.listener.OnNewTokenListener
import ru.rustore.sdk.universalpush.listener.OnPushClientErrorListener
import ru.rustore.sdk.universalpush.domain.model.UniversalRemoteMessage
import ru.rustore.sdk.universalpush.firebase.provides.FirebasePushProvider
import ru.rustore.sdk.universalpush.rustore.providers.RuStorePushProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пуш-транспорты. Сейчас один — RuStore; FCM и HMS добавляются здесь же, своими провайдерами в
 * `init`, и всё остальное в приложении об этом знать не должно: токены приходят словарём
 * «транспорт → токен», и сервер сам решает, куда отправлять.
 *
 * Приёмные сервисы объявляет сам SDK в своём манифесте, поэтому в нашем их нет.
 */
@Singleton
class PushClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PushTransport {
    /**
     * Без идентификатора проекта транспорт не поднять. Это не поломка: собранное без ключей
     * приложение работает как раньше, живая лента на месте — нет только уведомлений.
     */
    override val configured: Boolean get() = BuildConfig.RUSTORE_PROJECT_ID.isNotBlank()

    private val started = AtomicBoolean(false)

    /**
     * Слушатели ставятся один раз на процесс и обязательно из `Application.onCreate`: пуш на
     * закрытом приложении поднимает процесс, и к моменту доставки они должны уже стоять — иначе
     * сообщение приедет в пустоту.
     */
    override fun start(
        onMessage: (PushMessage) -> Unit,
        onToken: (provider: String, token: String) -> Unit,
    ) {
        if (!configured || !started.compareAndSet(false, true)) return

        RuStoreUniversalPushClient.init(
            context = context,
            rustore = RuStorePushProvider(
                application = context as Application,
                projectId = BuildConfig.RUSTORE_PROJECT_ID,
                logger = DefaultLogger(TAG),
            ),
            // Второй канал, а не запасной: доставка на телефон без RuStore иначе невозможна вовсе, а
            // отказ транспорта не виден отправителю — переключаться было бы не по чему. Дубль на
            // устройстве с обоими токенами гасят дедупликация SDK и наша по `messageId`.
            // Проект берётся из app/google-services.json, отдельного BuildConfig-поля тут нет.
            firebase = FirebasePushProvider(context = context),
        )

        RuStoreUniversalPushClient.setOnMessageReceiveListener(
            object : OnMessageReceivedListener {
                override fun onMessageReceived(message: UniversalRemoteMessage) {
                    // Чужие и незнакомые события пропускаем: канал общий, а разбирать мы умеем
                    // только сообщения переписки. В отладке это не молча: «уведомление не пришло»
                    // и «пришло, но мы его не поняли» выглядят одинаково, а чинятся по-разному.
                    // Печатаются поля, а не значения: в payload лежит текст ответа агента.
                    trace { "пуш от транспорта: поля ${message.data.keys}" }
                    val parsed = PushMessage.parse(message.data)
                    if (parsed == null) trace { "пуш пропущен: не наш формат" } else onMessage(parsed)
                }
            }
        )

        RuStoreUniversalPushClient.setOnNewTokenListener(
            object : OnNewTokenListener {
                override fun onNewToken(provider: String, token: String) {
                    onToken(provider, token)
                }
            }
        )

        RuStoreUniversalPushClient.setOnPushClientErrorListener(
            object : OnPushClientErrorListener {
                override fun onPushClientError(provider: String, errors: List<Throwable>) {
                    warn { "$provider: ${errors.joinToString { it.message.orEmpty() }}" }
                }
            }
        )
    }

    /** Токены всех поднятых транспортов: «транспорт → токен». */
    override suspend fun tokens(): Map<String, String> {
        if (!configured) return emptyMap()
        // `await()` у Task блокирующий, поэтому только на IO.
        return withContext(Dispatchers.IO) {
            runCatching { RuStoreUniversalPushClient.getTokens().await() }
                // Токен целиком — только в отладочной сборке: без него не отправить пробное
                // уведомление из консоли, а в релизе он в logcat не нужен никому.
                .onSuccess { tokens -> trace { "токены: $tokens" } }
                .onFailure { error -> warn(error) { "не удалось получить токены" } }
                .getOrDefault(emptyMap())
        }
    }

    /**
     * Отзыв токенов у транспорта — при выходе из аккаунта. Серверную запись чистит сам сервер,
     * закрывая сессию входа; здесь важно, чтобы транспорт перестал доставлять на это устройство.
     */
    override suspend fun dropTokens(tokens: Map<String, String>) {
        if (!configured || tokens.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching { RuStoreUniversalPushClient.deleteTokens(tokens).await() }
                .onFailure { error -> warn(error) { "не удалось отозвать токены" } }
        }
    }

    /**
     * Как и у real-time: в релизе этих строк нет. Они несут идентификаторы устройства и сессии,
     * logcat читает кто угодно, а разбирать поломку по ним может только тот, кто собрал debug.
     */
    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message())
    }

    private inline fun warn(error: Throwable? = null, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val text = message()
        if (error != null) Log.w(TAG, text, error) else Log.w(TAG, text)
    }

    private companion object {
        const val TAG = "AgiPush"
    }
}
