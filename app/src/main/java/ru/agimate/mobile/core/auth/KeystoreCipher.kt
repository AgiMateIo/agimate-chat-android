package ru.agimate.mobile.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM на ключе из Android Keystore.
 *
 * Спека требует хранить токены под ключом из Keystore и называет `EncryptedSharedPreferences`. Та
 * библиотека объявлена устаревшей и известна повреждением keyset'а на части прошивок — для
 * refresh-токена, который живёт два месяца, это означало бы регулярный необъяснимый разлогин.
 * Поэтому здесь то же самое, но напрямую: ключ не покидает Keystore, наружу уходит `iv || ciphertext`
 * в base64.
 *
 * Свой IV не задаётся намеренно: `setRandomizedEncryptionRequired` по умолчанию включён, и Keystore
 * сам выдаёт уникальный вектор на каждое шифрование.
 */
internal class KeystoreCipher(private val alias: String) {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, 1)
        encrypted.copyInto(packed, 1 + iv.size)
        return Base64.getEncoder().encodeToString(packed)
    }

    /**
     * `null`, если расшифровать не удалось: ключ перевыпущен системой, данные повреждены, бэкап
     * приехал с чужого устройства. Это не повод падать — это повод считать, что входа не было.
     */
    fun decrypt(encoded: String): String? = runCatching {
        val packed = Base64.getDecoder().decode(encoded)
        val ivSize = packed[0].toInt()
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val body = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    fun dropKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
