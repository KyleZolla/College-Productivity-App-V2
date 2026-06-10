package com.example.productivityapp

import android.content.Context

/**
 * Holds the PKCE code verifier between launching the browser for Google OAuth and
 * the redirect back into [AuthCallbackActivity].
 *
 * A callback that arrives without a matching pending verifier was not started by this
 * app and is rejected, which blocks forged `productivityapp://auth/callback` intents.
 */
object PendingOAuth {

    private const val PREFS_NAME = "pending_oauth"
    private const val KEY_CODE_VERIFIER = "code_verifier"
    private const val KEY_CREATED_AT_MS = "created_at_ms"

    /** OAuth round trips take seconds; anything older than this is stale. */
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    fun save(context: Context, codeVerifier: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .putLong(KEY_CREATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Returns the pending verifier and clears it (single use), or null when there is
     * no pending OAuth flow or it has expired.
     */
    fun consume(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val verifier = prefs.getString(KEY_CODE_VERIFIER, null)
        val createdAt = prefs.getLong(KEY_CREATED_AT_MS, 0L)
        prefs.edit().clear().apply()

        if (verifier.isNullOrBlank()) return null
        if (System.currentTimeMillis() - createdAt > MAX_AGE_MS) return null
        return verifier
    }
}
