package ru.agimate.mobile.core.auth

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Граница в байтах — единственное, ради чего эта проверка существует отдельно от сервера: по
 * символам такой пароль выглядит коротким, и форма, считающая символы, пропустила бы отказ.
 */
class PasswordRulesTest {

    @Test
    fun `eight characters are enough`() {
        assertNull(PasswordRules.problem("12345678"))
    }

    @Test
    fun `seven are not`() {
        assertNotNull(PasswordRules.problem("1234567"))
    }

    @Test
    fun `cyrillic runs out of bytes before it runs out of characters`() {
        // 40 букв по два байта — 80 при потолке в 72, а по символам пароль ещё короткий.
        val password = "п".repeat(40)
        assertNotNull(PasswordRules.problem(password))
        assertNull(PasswordRules.problem("п".repeat(36)))
    }

    @Test
    fun `emoji count as their bytes too`() {
        assertNotNull(PasswordRules.problem("🙂".repeat(19)))
    }
}
