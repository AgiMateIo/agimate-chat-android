package ru.agimate.mobile.core.auth

import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.core.ui.text.uiText

/**
 * Что сервер примет как пароль.
 *
 * Верхняя граница — в **байтах**, а не в символах, и это не придирка: алгоритм хеша читает первые
 * 72 байта и об остальном молчит. Сорок кириллических букв уже за границей, а по символам выглядят
 * коротким паролем — форма, считающая символы, пропустила бы то, что сервер отвергнет.
 *
 * Требований к составу (цифра, заглавная, спецсимвол) нет намеренно, и придумывать их на клиенте
 * нельзя: форма отказывала бы в паролях, которые сервер принимает.
 */
object PasswordRules {

    const val MIN_LENGTH = 8
    const val MAX_BYTES = 72

    /** `null` — пароль годится. Иначе — что с ним не так, словами для человека. */
    fun problem(password: String): UiText? = when {
        password.length < MIN_LENGTH -> uiText(R.string.password_too_short, MIN_LENGTH)
        password.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> uiText(R.string.password_too_long)
        else -> null
    }
}
