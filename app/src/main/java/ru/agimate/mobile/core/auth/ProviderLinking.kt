package ru.agimate.mobile.core.auth

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.agimate.mobile.R
import ru.agimate.mobile.core.di.ApplicationScope
import ru.agimate.mobile.core.network.ApiException
import ru.agimate.mobile.core.network.apiCall
import ru.agimate.mobile.core.network.toApiException
import ru.agimate.mobile.core.network.unwrap
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Чем кончилась привязка. Четыре исхода приходят с кодом 200, включая два отказа по существу. */
enum class LinkOutcome {
    /** Привязали. */
    LINKED,

    /** Этот же аккаунт провайдера уже был привязан — не ошибка, тот же экран успеха. */
    ALREADY_YOURS,

    /** Аккаунт провайдера принадлежит кому-то ещё; аккаунты не сливаются никогда. */
    TAKEN,

    /** У человека уже есть другой аккаунт этого провайдера: один провайдер — одна дверь. */
    PROVIDER_OCCUPIED,

    /** Сервер назвал исход, которого приложение не знает: показываем как отказ, а не как успех. */
    UNKNOWN;

    val success: Boolean get() = this == LINKED || this == ALREADY_YOURS

    companion object {
        fun of(raw: String?): LinkOutcome =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

sealed interface LinkState {
    data object Idle : LinkState

    /** Доказательство приехало, меняем его на связь с аккаунтом. */
    data class Working(val provider: AuthProvider?) : LinkState

    data class Done(val provider: AuthProvider?, val outcome: LinkOutcome) : LinkState

    data class Failed(val text: UiText) : LinkState
}

/**
 * Привязка провайдера к уже открытому аккаунту — четвёртый путь, единственный, который не выдаёт
 * токенов.
 *
 * Живёт синглтоном, а не во ViewModel экрана, по устройству возврата: доказательство приезжает в
 * тот же deep link, что и вход, то есть в Activity, — а экран способов входа к этому моменту может
 * быть и пересоздан, и закрыт. Обмен начинается сразу по возвращении и не ждёт, пока человек
 * что-нибудь нажмёт: пять минут жизни доказательства отведены на дорогу от колбэка до запроса, а не
 * на раздумья.
 */
@Singleton
class ProviderLinking @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: AuthRepository,
    private val api: AuthMethodsApi,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /**
     * Метка «круг привязки начали мы». Переживает смерть процесса намеренно: браузер поднимается
     * рядом, и убить нас в этот момент система может запросто — а вернувшееся доказательство
     * тогда окажется честным, но непринятым.
     *
     * Секрета здесь нет: имя провайдера и время. Обычное хранилище, без Keystore.
     */
    private val marks = context.getSharedPreferences(MARKS, Context.MODE_PRIVATE)

    /**
     * Провайдер, за которым ушли в браузер. Он же признак «идёт привязка, а не вход»: возврат с
     * `?error=` в обоих путях выглядит одинаково, и различить их больше нечем.
     *
     * Живёт в памяти и гаснет на первом же возврате в приложение ([abandon]) — это состояние
     * экрана, а не разрешение принять доказательство. Разрешение — в [marks].
     */
    private val pending = AtomicReference<AuthProvider?>(null)

    /**
     * Доказательство, которое уже погасили. Тот же intent приезжает в Activity второй раз при её
     * пересоздании — от поворота экрана, например, — и без этой памяти второй заход получил бы 403
     * на потраченном доказательстве и показал отказ поверх удавшейся привязки.
     */
    private val spent = AtomicReference<String?>(null)

    val awaiting: Boolean get() = pending.get() != null

    /** Адрес круга привязки. Открывать — в системном браузере, как и вход. */
    fun begin(provider: AuthProvider): Uri {
        pending.set(provider)
        marks.edit {
            putString(KEY_PROVIDER, provider.code)
            putLong(KEY_STARTED_AT, System.currentTimeMillis())
        }
        _state.value = LinkState.Idle
        return repository.linkingUri(provider)
    }

    /**
     * Круг не дошёл до конца: провайдер отказал, или человек закрыл вкладку.
     *
     * Метку не трогаем. Зовётся это на каждом возврате в приложение, а вернуться человек может и
     * посреди круга — переключился на другое приложение и обратно. Стерев метку здесь, мы отвергли
     * бы доказательство, которого сами же и просили минуту назад.
     */
    fun abandon() {
        pending.set(null)
    }

    fun failed(text: UiText = uiText(R.string.link_failed_retry)) {
        pending.set(null)
        _state.value = LinkState.Failed(text)
    }

    /**
     * Второй шаг: доказательство меняется на связь с **этим** аккаунтом — тем, чей токен уйдёт в
     * заголовке. Провайдер из редиректа нужен только для текста на экране; что именно привязано,
     * говорит ответ.
     *
     * Доказательство, которого не просили, выбрасывается: круг должно было начать это приложение.
     * Сервер связывает провайдера с аккаунтом по заголовку `Authorization` и держится на том, что
     * заголовок пошлёт только своя страница, — но здесь его посылаем мы, сами, за любой пришедший
     * `link_proof`. Без этой проверки хватает подсунуть телефону ссылку с чужим доказательством
     * (ссылкой в переписке, редиректом со страницы, интентом от другого приложения — Activity
     * экспортирована), чтобы аккаунт провайдера злоумышленника стал дверью в аккаунт жертвы. И
     * молча: [state] читает только экран способов входа, а его в этот момент может не быть.
     *
     * Молчим и мы: показать по такой ссылке нечего, любое сообщение стало бы текстом, который
     * выбрал чужой человек, и временем, которое выбрал он же.
     *
     * Метка живёт ровно столько, сколько само доказательство. Просроченная не хуже отсутствующей:
     * сервер такое доказательство всё равно не примет, а окно, в которое подсунутое чужое сойдёт
     * за наше, сужается до пяти минут после того, как человек сам нажал «привязать».
     */
    fun redeem(proof: String, provider: AuthProvider?) {
        val started = marks.getLong(KEY_STARTED_AT, 0L)
        if (started == 0L || System.currentTimeMillis() - started > PROOF_LIFETIME_MS) return
        if (spent.getAndSet(proof) == proof) return

        val known = provider ?: AuthProvider.of(marks.getString(KEY_PROVIDER, null)) ?: pending.get()
        marks.edit { clear() }
        pending.set(null)
        _state.value = LinkState.Working(known)

        scope.launch {
            try {
                val result = apiCall { api.link(LinkProofRequest(proof)) }
                    .unwrap("привязка провайдера")
                _state.value = LinkState.Done(
                    provider = AuthProvider.of(result.provider) ?: known,
                    outcome = LinkOutcome.of(result.outcome),
                )
            } catch (e: Throwable) {
                _state.value = LinkState.Failed(
                    when (val failure = e.toApiException()) {
                        // Просрочено, потрачено или подделано — круг придётся пройти заново.
                        is ApiException.Forbidden -> uiText(R.string.link_proof_expired)
                        else -> failure.text
                    }
                )
            }
        }
    }

    /** Экран показал исход — дальше состояние жить не должно. */
    fun consume() {
        _state.value = LinkState.Idle
    }

    private companion object {
        const val MARKS = "provider_linking"
        const val KEY_PROVIDER = "provider"
        const val KEY_STARTED_AT = "started_at"

        /** Столько живёт доказательство на сервере. Дольше метки держать незачем. */
        const val PROOF_LIFETIME_MS = 5 * 60 * 1000L
    }
}
