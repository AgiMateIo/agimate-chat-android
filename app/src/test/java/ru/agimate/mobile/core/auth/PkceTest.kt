package ru.agimate.mobile.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Требования сервера к PKCE проверяются молча: challenge неверной длины или с символами вне
 * base64url просто игнорируется, и вход заканчивается `?error=invalid_request`. Поэтому длина и
 * алфавит здесь — тест, а не комментарий.
 */
class PkceTest {

    private val base64Url = Regex("^[A-Za-z0-9\\-_]+$")

    @Test
    fun `verifier is exactly 43 characters of base64url`() {
        repeat(50) {
            val verifier = Pkce.createVerifier()
            assertEquals(43, verifier.length)
            assertTrue(verifier, base64Url.matches(verifier))
        }
    }

    @Test
    fun `challenge is exactly 43 characters of base64url`() {
        repeat(50) {
            val challenge = Pkce.challengeOf(Pkce.createVerifier())
            assertEquals(43, challenge.length)
            assertTrue(challenge, base64Url.matches(challenge))
        }
    }

    /** Контрольный вектор из RFC 7636, приложение B. */
    @Test
    fun `challenge matches the RFC 7636 vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf(verifier))
    }

    @Test
    fun `verifiers do not repeat`() {
        val generated = List(200) { Pkce.createVerifier() }
        assertEquals(generated.size, generated.toSet().size)
    }
}
