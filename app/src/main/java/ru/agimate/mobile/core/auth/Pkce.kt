package ru.agimate.mobile.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE S256. Требования сервера жёсткие и проверяются молча: challenge неверной длины или с
 * символами вне base64url просто игнорируется, и вход заканчивается `?error=invalid_request` — без
 * объяснения, что именно не так.
 *
 * - verifier — 43–128 символов из `A–Z a–z 0–9 - . _ ~`; 32 случайных байта в base64url без
 *   паддинга дают ровно 43;
 * - challenge — base64url-без-паддинга от SHA-256(ASCII(verifier)), ровно 43 символа;
 * - метод только S256; параметр `code_challenge_method` сервер не читает вовсе.
 */
object Pkce {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun createVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }
}
