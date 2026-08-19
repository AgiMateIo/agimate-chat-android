package ru.agimate.mobile.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.agimate.mobile.core.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Какой вход сейчас на устройстве — и ничего больше.
 *
 * Отделено от [SessionManager] намеренно. Тот отвечает на вопрос «что человеку можно» и ради ответа
 * ходит за профилем; «кто вошёл» нужно и там, где профиль спрашивать не у кого и незачем — например
 * в процессе, который пуш поднял при закрытом приложении.
 *
 * Смысл в том, что интерпретация одна. Пока хранилище токенов читали двое, «вход сменился»
 * существовало в двух экземплярах, и каждый тянул за собой свой побочный эффект: подписка на пуши
 * уходила на сервер дважды.
 *
 * Идентификатор входа, а не «вошёл ли по-настоящему»: `GUEST`, ждущий одобрения, — такой же вход,
 * и уведомления ему нужны так же.
 */
@Singleton
class CurrentSession @Inject constructor(
    tokens: TokenStore,
    @param:ApplicationScope scope: CoroutineScope,
) {
    /** Строка этого устройства в списке входов; `null` — входа нет. */
    val id: StateFlow<String?> = tokens.tokens
        .map { it?.sessionId }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, tokens.load()?.sessionId)

    val current: String? get() = id.value
}
