package com.example.productivityapp

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant

/**
 * Reads usage counts from `public.ai_usage_events` to drive the app-side free-tier
 * experience checks for the two AI actions in the app.
 *
 * Event types written (server-side, by the Edge Functions) and counted here:
 * - [EVENT_COMPLEX_TASK_CREATION] — AI roadmap generation for complex tasks.
 * - [EVENT_COURSE_SYLLABUS_PROFILE] — AI course profile generation from a syllabus.
 *
 * This object only *reads* counts. It never inserts rows: the Edge Functions are the
 * source of truth and must also enforce these limits server-side before calling OpenAI.
 *
 * Failures fail open (treated as "not over the limit") so transient network/REST issues
 * never block a user from a feature; the server-side check remains the hard gate.
 */
object SupabaseAiUsageApi {

    private const val LOG_TAG = "SupabaseAiUsageApi"

    const val EVENT_COMPLEX_TASK_CREATION = "complex_task_creation"
    const val EVENT_COURSE_SYLLABUS_PROFILE = "course_syllabus_profile"

    /** Free MVP limit: 5 complex AI roadmaps per rolling 7 days. */
    const val COMPLEX_ROADMAP_LIMIT = 5
    private const val COMPLEX_ROADMAP_WINDOW_DAYS = 7L

    /** Free MVP limit: 10 AI syllabus/course profiles per rolling 90 days. */
    const val SYLLABUS_PROFILE_LIMIT = 10
    private const val SYLLABUS_PROFILE_WINDOW_DAYS = 90L

    /**
     * True when the user has reached the free complex-roadmap limit in the rolling window.
     * Returns false when the count cannot be determined (fail open).
     */
    fun isComplexRoadmapLimitReached(accessToken: String, userId: String): Boolean {
        val count = countRecentEvents(
            accessToken = accessToken,
            userId = userId,
            eventType = EVENT_COMPLEX_TASK_CREATION,
            windowDays = COMPLEX_ROADMAP_WINDOW_DAYS,
        ) ?: return false
        return count >= COMPLEX_ROADMAP_LIMIT
    }

    /**
     * True when the user has reached the free syllabus-profile limit in the rolling window.
     * Returns false when the count cannot be determined (fail open).
     */
    fun isSyllabusProfileLimitReached(accessToken: String, userId: String): Boolean {
        val count = countRecentEvents(
            accessToken = accessToken,
            userId = userId,
            eventType = EVENT_COURSE_SYLLABUS_PROFILE,
            windowDays = SYLLABUS_PROFILE_WINDOW_DAYS,
        ) ?: return false
        return count >= SYLLABUS_PROFILE_LIMIT
    }

    /**
     * Counts rows in `ai_usage_events` for [userId] / [eventType] created within the last
     * [windowDays]. Returns null on any error (network, auth, parse) so callers can fail open.
     */
    private fun countRecentEvents(
        accessToken: String,
        userId: String,
        eventType: String,
        windowDays: Long,
    ): Int? {
        if (accessToken.isBlank() || userId.isBlank()) return null
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) return null

        val sinceIso = Instant.now().minus(Duration.ofDays(windowDays)).toString()
        return try {
            val base = BuildConfig.SUPABASE_URL.trimEnd('/')
            val query = buildString {
                append("select=id")
                append("&user_id=eq.").append(enc(userId))
                append("&event_type=eq.").append(enc(eventType))
                append("&created_at=gte.").append(enc(sinceIso))
            }
            val url = URL("$base/rest/v1/ai_usage_events?$query")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                // Ask PostgREST for the exact total in the Content-Range header.
                connection.setRequestProperty("Prefer", "count=exact")
                connection.setRequestProperty("Range-Unit", "items")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w(LOG_TAG, "ai_usage_events count failed ($responseCode) for '$eventType'")
                    return null
                }
                parseContentRangeTotal(connection.getHeaderField("Content-Range"))
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "ai_usage_events count error for '$eventType'", e)
            null
        }
    }

    /** Parses the total from a PostgREST `Content-Range` header (e.g. `0-4/12` or `* /12`). */
    private fun parseContentRangeTotal(contentRange: String?): Int? {
        val total = contentRange?.substringAfter('/', "")?.trim().orEmpty()
        if (total.isEmpty() || total == "*") return null
        return total.toIntOrNull()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
