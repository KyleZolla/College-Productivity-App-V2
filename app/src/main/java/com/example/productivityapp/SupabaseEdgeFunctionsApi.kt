package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

object SupabaseEdgeFunctionsApi {

    // IMPORTANT: this must match the deployed Edge Function HTTP slug.
    // In your project, the deployed URL is `/functions/v1/super-handler` even if the local folder is `get_roadmap`.
    private const val PRIMARY_ROADMAP_FUNCTION_SLUG = "super-handler"
    private const val FALLBACK_ROADMAP_FUNCTION_SLUG = "get_roadmap"

    sealed class RoadmapResult {
        data class Success(val steps: JSONArray, val totalEstimatedHours: Double) : RoadmapResult()
        data class Failure(
            val message: String,
            val kind: FailureKind = FailureKind.GENERATION,
        ) : RoadmapResult()

        /** Server reported the free AI limit was reached. [message] is the user-facing text. */
        data class LimitReached(val message: String) : RoadmapResult()

        enum class FailureKind {
            GENERATION,
            INCOMPLETE_RESPONSE,
        }
    }

    sealed class CourseProfileResult {
        data class Success(val courseProfile: String) : CourseProfileResult()
        data class Failure(val message: String) : CourseProfileResult()

        /** Server reported the free AI limit was reached. [message] is the user-facing text. */
        data class LimitReached(val message: String) : CourseProfileResult()
    }

    private const val GENERATE_COURSE_PROFILE_SLUG = "generate-course-profile"

    /** Error code returned by the Edge Functions when the free AI usage limit is reached. */
    private const val AI_LIMIT_REACHED_ERROR = "AI_LIMIT_REACHED"

    fun getRoadmap(
        accessToken: String,
        title: String,
        dueDateIso: String,
        assignmentType: String?,
        difficulty: String?,
        requirements: String?,
        documentContent: String?,
        photoText: String?,
        courseName: String? = null,
        courseLevel: String? = null,
        courseProfile: String? = null,
        school: String? = null,
        yearInSchool: String? = null,
        userEstimatedHours: Double? = null,
        existingWorkload: Map<String, Double> = emptyMap(),
    ): RoadmapResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return RoadmapResult.Failure("Missing Supabase config.")
        }
        val payload = JSONObject()
            .put("timeZone", ZoneId.systemDefault().id)
            .put("title", title)
            .put("dueDate", dueDateIso)
            .put("assignmentType", assignmentType ?: JSONObject.NULL)
            .put("difficulty", difficulty ?: JSONObject.NULL)
            .put("requirements", requirements ?: JSONObject.NULL)
            .put("documentContent", documentContent ?: JSONObject.NULL)
            .put("photoText", photoText ?: JSONObject.NULL)
            .put("courseName", courseName ?: JSONObject.NULL)
            .put("courseLevel", courseLevel ?: JSONObject.NULL)
            .put("courseProfile", courseProfile ?: JSONObject.NULL)
            .put("school", school?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("yearInSchool", yearInSchool?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        if (userEstimatedHours != null) {
            payload.put("userEstimatedHours", userEstimatedHours)
        } else {
            payload.put("userEstimatedHours", JSONObject.NULL)
        }

        val estimateFeedbackHistory = loadEstimateFeedbackHistory(accessToken)
        payload.put("estimateFeedbackHistory", estimateFeedbackHistory)

        val existingWorkloadJson = JSONObject()
        for ((date, hours) in existingWorkload) {
            existingWorkloadJson.put(date, hours)
        }
        payload.put("existingWorkload", existingWorkloadJson)

        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val primary = callRoadmapFunction(base, PRIMARY_ROADMAP_FUNCTION_SLUG, accessToken, payload)
        if (primary is RoadmapResult.Failure && primary.message.contains("HTTP 404", ignoreCase = true)) {
            val fallback = callRoadmapFunction(base, FALLBACK_ROADMAP_FUNCTION_SLUG, accessToken, payload)
            return if (fallback is RoadmapResult.Failure) {
                RoadmapResult.Failure(
                    primary.message + "\n\nTried fallback `${FALLBACK_ROADMAP_FUNCTION_SLUG}` too:\n" + fallback.message
                )
            } else {
                fallback
            }
        }
        return primary
    }

    fun generateCourseProfile(
        accessToken: String,
        courseName: String,
        courseLevel: String,
        courseSyllabus: String,
    ): CourseProfileResult {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return CourseProfileResult.Failure("Missing Supabase config.")
        }
        if (accessToken.isBlank()) {
            return CourseProfileResult.Failure("Not signed in.")
        }
        val payload = JSONObject()
            .put("courseName", courseName.trim())
            .put("courseLevel", courseLevel.trim())
            .put("courseSyllabus", courseSyllabus.trim())
        return callGenerateCourseProfileFunction(
            BuildConfig.SUPABASE_URL.trimEnd('/'),
            GENERATE_COURSE_PROFILE_SLUG,
            accessToken,
            payload,
        )
    }

    private fun callGenerateCourseProfileFunction(
        base: String,
        slug: String,
        accessToken: String,
        payload: JSONObject,
    ): CourseProfileResult {
        return try {
            val url = URL("$base/functions/v1/$slug")
            Log.d("SupabaseEdgeFunctionsApi", "Calling Edge Function: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            connection.doOutput = true

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            parseAiLimitReachedMessage(responseBody)?.let { message ->
                Log.w("SupabaseEdgeFunctionsApi", "Course profile Edge Function returned AI_LIMIT_REACHED")
                return CourseProfileResult.LimitReached(message)
            }

            if (responseCode !in 200..299) {
                val reason = parseError(responseBody, responseCode)
                val msg = "HTTP $responseCode at $url\n$reason"
                Log.e("SupabaseEdgeFunctionsApi", msg)
                return CourseProfileResult.Failure(msg)
            }

            parseCourseProfile(responseBody) ?: run {
                val snippet = responseBody.trim().take(800)
                CourseProfileResult.Failure(
                    "200 OK at $url but response did not contain courseProfile.\n\nBody snippet:\n$snippet",
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Network error."
            Log.e("SupabaseEdgeFunctionsApi", msg, e)
            CourseProfileResult.Failure(msg)
        }
    }

    private fun parseCourseProfile(body: String): CourseProfileResult.Success? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val profile = when {
                        obj.has("courseProfile") && !obj.isNull("courseProfile") ->
                            obj.optString("courseProfile")
                        obj.has("data") && !obj.isNull("data") -> {
                            val data = obj.opt("data")
                            when (data) {
                                is JSONObject ->
                                    if (data.has("courseProfile") && !data.isNull("courseProfile")) {
                                        data.optString("courseProfile")
                                    } else {
                                        null
                                    }
                                else -> null
                            }
                        }
                        else -> null
                    }
                    profile?.trim()?.takeIf { it.isNotEmpty() }?.let { CourseProfileResult.Success(it) }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadEstimateFeedbackHistory(accessToken: String): JSONArray {
        val userId = SupabaseUserId.resolveUserId(accessToken) ?: return JSONArray()
        return when (
            val result = SupabaseRoadmapStepEstimateFeedbackApi.listRecentHistory(accessToken, userId, limit = 50)
        ) {
            is SupabaseRoadmapStepEstimateFeedbackApi.HistoryResult.Success -> result.records
            is SupabaseRoadmapStepEstimateFeedbackApi.HistoryResult.Failure -> {
                Log.w("SupabaseEdgeFunctionsApi", "Could not load estimate feedback history: ${result.message}")
                JSONArray()
            }
        }
    }

    private fun callRoadmapFunction(
        base: String,
        slug: String,
        accessToken: String,
        payload: JSONObject,
    ): RoadmapResult {
        return try {
            val url = URL("$base/functions/v1/$slug")
            Log.d("SupabaseEdgeFunctionsApi", "Calling Edge Function: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.doOutput = true

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            parseAiLimitReachedMessage(responseBody)?.let { message ->
                Log.w("SupabaseEdgeFunctionsApi", "Roadmap Edge Function returned AI_LIMIT_REACHED")
                return RoadmapResult.LimitReached(message)
            }

            if (responseCode !in 200..299) {
                val reason = parseError(responseBody, responseCode)
                val msg = "HTTP $responseCode at $url\n$reason"
                Log.e("SupabaseEdgeFunctionsApi", msg)
                return RoadmapResult.Failure(msg)
            }

            val parsed = parseRoadmapResponse(responseBody) ?: run {
                val snippet = responseBody.trim().take(800)
                return RoadmapResult.Failure(
                    "200 OK at $url but response did not contain steps.\n\nBody snippet:\n$snippet",
                    RoadmapResult.FailureKind.INCOMPLETE_RESPONSE,
                )
            }
            if (!GeneratedRoadmapValidator.validate(parsed.steps, parsed.totalEstimatedHours)) {
                return RoadmapResult.Failure(
                    "Roadmap response missing required fields.",
                    RoadmapResult.FailureKind.INCOMPLETE_RESPONSE,
                )
            }
            RoadmapResult.Success(parsed.steps, parsed.totalEstimatedHours!!)
        } catch (e: Exception) {
            val msg = e.message ?: "Network error."
            Log.e("SupabaseEdgeFunctionsApi", msg, e)
            RoadmapResult.Failure(msg)
        }
    }

    private data class ParsedRoadmapResponse(
        val steps: JSONArray,
        val totalEstimatedHours: Double?,
    )

    private fun parseRoadmapResponse(body: String): ParsedRoadmapResponse? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed.startsWith("[") -> ParsedRoadmapResponse(JSONArray(trimmed), null)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val steps = when {
                        obj.has("steps") && !obj.isNull("steps") -> obj.optJSONArray("steps")
                        obj.has("data") && !obj.isNull("data") -> {
                            val data = obj.opt("data")
                            when (data) {
                                is JSONArray -> data
                                is JSONObject -> data.optJSONArray("steps")
                                else -> null
                            }
                        }
                        else -> null
                    } ?: return null
                    val totalHours = parseTotalEstimatedHours(obj)
                    ParsedRoadmapResponse(steps, totalHours)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTotalEstimatedHours(obj: JSONObject): Double? {
        val candidates = listOf("totalEstimatedHours", "total_estimated_hours")
        for (key in candidates) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optDouble(key)
            if (!value.isNaN()) return value
        }
        if (obj.has("data") && !obj.isNull("data")) {
            val data = obj.opt("data")
            if (data is JSONObject) return parseTotalEstimatedHours(data)
        }
        return null
    }

    /**
     * Detects the `{ "error": "AI_LIMIT_REACHED", "message": "..." }` response that both AI Edge
     * Functions return when the free usage limit is hit (regardless of HTTP status code).
     *
     * Returns the user-facing message when it is a limit response (empty string if no message was
     * supplied, so the caller can fall back), or null when it is not a limit response.
     */
    private fun parseAiLimitReachedMessage(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            val obj = JSONObject(trimmed)
            val error = obj.optString("error").trim()
            if (!error.equals(AI_LIMIT_REACHED_ERROR, ignoreCase = true)) return null
            obj.optString("message").trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseError(body: String, code: Int): String {
        return try {
            val root = JSONObject(body)
            root.optString("message")
                .ifBlank { root.optString("error") }
                .ifBlank { root.optString("error_description") }
                .ifBlank { root.optString("hint") }
                .ifBlank { root.optString("details") }
                .ifBlank { "Request failed ($code)" }
        } catch (_: Exception) {
            if (body.isBlank()) "Request failed ($code)" else body
        }
    }
}

