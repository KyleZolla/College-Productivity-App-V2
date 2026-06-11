package com.example.productivityapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Stores the Supabase session. Tokens are encrypted at rest with an Android
 * Keystore key ([TokenCipher]); `auth_prefs` is also excluded from backups
 * (see `backup_rules.xml` / `data_extraction_rules.xml`).
 *
 * Sessions saved as plaintext by older app versions are migrated to encrypted
 * storage on first read. Plaintext is only written as a fallback on devices
 * with a broken Keystore, matching the old behavior instead of locking users out.
 */
object SessionManager {
    private const val TAG = "SessionManager"
    private const val PREFS_NAME = "auth_prefs"

    // Legacy plaintext keys (pre-encryption); still read for migration/fallback.
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    private const val KEY_ACCESS_TOKEN_ENC = "access_token_enc"
    private const val KEY_REFRESH_TOKEN_ENC = "refresh_token_enc"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long?
    ) {
        val expiresAtMs = expiresInSeconds?.let { System.currentTimeMillis() + (it * 1000L) } ?: 0L
        val editor = prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN_ENC)
            .remove(KEY_REFRESH_TOKEN_ENC)
            .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)

        val encryptedAccess = TokenCipher.encrypt(accessToken)
        if (encryptedAccess != null) {
            editor.putString(KEY_ACCESS_TOKEN_ENC, encryptedAccess)
            TokenCipher.encrypt(refreshToken)?.let { editor.putString(KEY_REFRESH_TOKEN_ENC, it) }
        } else {
            Log.w(TAG, "Keystore unavailable; storing session without encryption.")
            editor.putString(KEY_ACCESS_TOKEN, accessToken)
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        editor.apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        val p = prefs(context)
        val hasToken = !p.getString(KEY_ACCESS_TOKEN_ENC, null).isNullOrBlank() ||
            !p.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
        if (!hasToken) return false

        val expiresAt = p.getLong(KEY_EXPIRES_AT_MS, 0L)
        return expiresAt == 0L || System.currentTimeMillis() < expiresAt
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN_ENC)
            .remove(KEY_REFRESH_TOKEN_ENC)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
    }

    fun getAccessToken(context: Context): String? {
        if (!isLoggedIn(context)) return null
        val p = prefs(context)

        val encrypted = p.getString(KEY_ACCESS_TOKEN_ENC, null)
        if (!encrypted.isNullOrBlank()) {
            val token = TokenCipher.decrypt(encrypted)
            if (token.isNullOrBlank()) {
                // Key was invalidated or data corrupted: the session is unrecoverable.
                Log.w(TAG, "Stored session could not be decrypted; clearing it.")
                clearSession(context)
                return null
            }
            return token
        }

        // Legacy plaintext session from an older app version: migrate it.
        val plaintext = p.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        migrateLegacySession(p, plaintext)
        return plaintext
    }

    private fun migrateLegacySession(p: SharedPreferences, accessToken: String) {
        val encryptedAccess = TokenCipher.encrypt(accessToken) ?: return
        val editor = p.edit()
            .putString(KEY_ACCESS_TOKEN_ENC, encryptedAccess)
            .remove(KEY_ACCESS_TOKEN)
        p.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { refresh ->
            TokenCipher.encrypt(refresh)?.let { editor.putString(KEY_REFRESH_TOKEN_ENC, it) }
        }
        editor.remove(KEY_REFRESH_TOKEN)
        editor.apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
