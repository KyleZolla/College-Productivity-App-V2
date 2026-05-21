package com.example.productivityapp

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object FcmTokenRegistrar {

    private const val TAG = "FcmTokenRegistrar"

    private val executor = Executors.newSingleThreadExecutor()

    /** Fetch the device FCM token and save it when a session exists. */
    fun syncIfLoggedIn(context: Context) {
        if (!SessionManager.isLoggedIn(context)) return
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "FCM token fetch failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    saveToken(context.applicationContext, token)
                } else {
                    Log.w(TAG, "FCM token fetch returned empty")
                }
            }
    }

    fun saveToken(context: Context, token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val accessToken = SessionManager.getAccessToken(appContext) ?: return@execute
                val userId = SupabaseUserId.resolveUserId(accessToken) ?: return@execute

                val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
                val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
                if (supabaseUrl.isBlank() || supabaseKey.isBlank()) return@execute

                val idEncoded = URLEncoder.encode(userId, StandardCharsets.UTF_8.name())
                val url = URL("$supabaseUrl/rest/v1/profiles?id=eq.$idEncoded")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PATCH"
                connection.setRequestProperty("apikey", supabaseKey)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Prefer", "return=minimal")
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.doOutput = true

                val payload = JSONObject().put("fcmToken", trimmed)
                val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { it.write(bodyBytes) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.e(TAG, "Failed to save FCM token to profile: HTTP $code")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to save FCM token to profile", e)
            }
        }
    }
}
