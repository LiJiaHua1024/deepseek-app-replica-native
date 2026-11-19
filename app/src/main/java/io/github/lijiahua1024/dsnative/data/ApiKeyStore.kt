package io.github.lijiahua1024.dsnative.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key storage: AES/GCM/NoPadding under AndroidKeyStore alias "deepseek-api-key",
 * ciphertext (12-byte IV prefix) Base64-NO_WRAP in SharedPreferences "credentials".
 * Mirrors android/.../ApiKeyStore.java of the WebView reference.
 */
class ApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun read(): String? {
        val stored = preferences.getString(VALUE, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            if (bytes.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun write(value: String?): Boolean {
        if (value.isNullOrEmpty()) {
            return preferences.edit().remove(VALUE).commit()
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val bytes = Arrays.copyOf(iv, iv.size + encrypted.size)
            System.arraycopy(encrypted, 0, bytes, iv.size, encrypted.size)
            preferences.edit().putString(VALUE, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        } catch (e: Exception) {
            false
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "deepseek-api-key"
        private const val VALUE = "encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
