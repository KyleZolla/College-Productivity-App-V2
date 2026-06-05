package com.example.productivityapp

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Fire-and-forget analytics for the `public.app_events` table via PostgREST.
 *
 * Design rules:
 * - Never blocks the UI: every insert runs on a dedicated background executor.
 * - Never crashes the app: all failures are caught and only logged to logcat.
 * - Reuses the app's existing Supabase access pattern (HttpURLConnection + anon key + user JWT).
 */
object AppEventsApi {

    private const val LOG_TAG = "AppEventsApi"

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Logs an analytics event using the currently stored session.
     * No-ops silently when the user is not signed in.
     */
    fun logAppEvent(
        context: Context,
        eventName: String,
        taskId: String? = null,
        courseId: String? = null,
        metadata: JSONObject = JSONObject(),
    ) {
        val accessToken = SessionManager.getAccessToken(context) ?: return
        logAppEvent(accessToken, eventName, taskId, courseId, metadata)
    }

    /**
     * Logs an analytics event when an access token is already available
     * (e.g. inside background create jobs that already resolved the session).
     */
    fun logAppEvent(
        accessToken: String,
        eventName: String,
        taskId: String? = null,
        courseId: String? = null,
        metadata: JSONObject = JSONObject(),
    ) {
        if (eventName.isBlank()) return
        if (accessToken.isBlank()) return
        // Snapshot metadata to a string now so later caller-side mutations cannot affect the row.
        val metadataJson = try {
            metadata.toString()
        } catch (_: Exception) {
            "{}"
        }
        executor.execute {
            try {
                if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) return@execute
                val userId = SupabaseUserId.resolveUserId(accessToken) ?: return@execute
                insertEvent(accessToken, userId, eventName, taskId, courseId, metadataJson)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to log app event '$eventName'", e)
            }
        }
    }

    private fun insertEvent(
        accessToken: String,
        userId: String,
        eventName: String,
        taskId: String?,
        courseId: String?,
        metadataJson: String,
    ) {
        try {
            val url = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/app_events")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Prefer", "return=minimal")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                val payload = JSONObject()
                    .put("user_id", userId)
                    .put("event_name", eventName)
                    .put("task_id", taskId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("course_id", courseId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("metadata", JSONObject(metadataJson))
                    .put("created_at", Instant.now().toString())

                val body = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(LOG_TAG, "app_events insert failed ($responseCode) for '$eventName': $errorBody")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "app_events insert error for '$eventName'", e)
        }
    }

    /** Trims a free-form error message to at most [max] characters for safe metadata storage. */
    fun shortenError(message: String?, max: Int = 200): String {
        val s = message?.trim().orEmpty()
        return if (s.length <= max) s else s.take(max)
    }
}
