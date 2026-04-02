package com.example.productivityapp

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long?
    ) {
        val expiresAtMs = expiresInSeconds?.let { System.currentTimeMillis() + (it * 1000L) } ?: 0L
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        if (accessToken.isBlank()) return false

        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        return expiresAt == 0L || System.currentTimeMillis() < expiresAt
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
    }
}
