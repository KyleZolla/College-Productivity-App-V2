package com.example.productivityapp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small strings (session tokens) with an AES-256-GCM key held in the Android
 * Keystore. The key is generated on-device and is not exportable, so ciphertext from a
 * backup or another device cannot be decrypted.
 *
 * Both operations return null on failure (e.g. corrupted keystore on some OEM devices)
 * so callers can degrade gracefully instead of crashing.
 */
object TokenCipher {

    private const val TAG = "TokenCipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "session_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /** Returns base64(iv || ciphertext), or null if encryption is unavailable. */
    fun encrypt(plaintext: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Token encryption failed", e)
            null
        }
    }

    /** Decrypts a value produced by [encrypt], or null when it cannot be decrypted. */
    fun decrypt(encoded: String): String? {
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= GCM_IV_BYTES) return null
            val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey() ?: return null, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Token decryption failed", e)
            null
        }
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
